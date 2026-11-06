package br.com.ritmo.nativo

import org.json.JSONObject
import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Importações offline: formatos genéricos reconhecidos. NÃO equivale a uma API oficial No Diet/Fitdays. */
object ExternalImport {
    private fun norm(s:String):String=Normalizer.normalize(s.trim().lowercase(),Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"").replace(Regex("[^a-z0-9]"),"")
    private fun csvParts(line:String, sep:Char):List<String> {
        val result=mutableListOf<String>();val b=StringBuilder();var quoted=false;var i=0
        while(i<line.length){val c=line[i]
            if(c=='"') { if(quoted && i+1<line.length && line[i+1]=='"') {b.append('"');i++} else quoted=!quoted }
            else if(c==sep && !quoted){result.add(b.toString().trim());b.clear()} else b.append(c)
            i++
        }
        result.add(b.toString().trim());return result
    }
    private fun parseLocal(s:String):LocalDateTime? {
        val v=s.trim().trim('"'); if(v.isBlank())return null
        try { return Instant.parse(v).atZone(ZoneId.systemDefault()).toLocalDateTime() } catch(_:Exception){}
        try {return OffsetDateTime.parse(v).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()}catch(_:Exception){}
        val patterns=listOf("yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm","yyyy-MM-dd'T'HH:mm:ss","yyyy-MM-dd'T'HH:mm", "dd/MM/yyyy HH:mm:ss","dd/MM/yyyy HH:mm","dd-MM-yyyy HH:mm","MM/dd/yyyy HH:mm:ss","MM/dd/yyyy HH:mm")
        for(p in patterns)try{return LocalDateTime.parse(v,DateTimeFormatter.ofPattern(p))}catch(_:Exception){}
        val dates=listOf("yyyy-MM-dd","dd/MM/yyyy","dd-MM-yyyy","MM/dd/yyyy")
        for(p in dates)try{return LocalDate.parse(v,DateTimeFormatter.ofPattern(p)).atStartOfDay()}catch(_:Exception){}
        return null
    }
    private fun number(v:String):Double? {
        var s=v.trim().replace("kg","",true).replace("%","").replace(" ","")
        if(s.count{it==','}==1 && s.count{it=='.'}==0)s=s.replace(',','.')
        else if(s.count{it==','}==1 && s.count{it=='.'}==1)s=s.replace(".","").replace(',','.')
        return s.toDoubleOrNull()?.takeIf {it.isFinite()}
    }
    fun importCsv(db:RitmoDb,profile:String,text:String,source:String):Pair<Int,Int>{
        val lines=text.trimStart('\uFEFF').lines().filter { it.isNotBlank() }
        if(lines.size<2)throw IllegalArgumentException("CSV vazio ou sem linhas de dados")
        val sep=if(lines.first().count{it==';'}>lines.first().count{it==','})';' else ','
        val heads=csvParts(lines.first(),sep).map(::norm)
        fun col(vararg aliases:String):Int = heads.indexOfFirst {it in aliases.map(::norm)}
        val dateIdx=col("date","data","measurement date","measurement_time","datetime","time","weighing date","data da medicao")
        val weightIdx=col("weight","peso","weight kg","weight(kg)","weight_kg","bodyweight")
        val fatIdx=col("body fat","bodyfat","bodyfatpercent","fatpercentage","fat%","gordura","gordura corporal")
        val muscleIdx=col("muscle mass","musclemass","muscle","musculo","massa muscular")
        val startIdx=col("start","start time","starttime","startedat","inicio","inicio jejum","fast start","fasting start")
        val endIdx=col("end","end time","endtime","endedat","fim","fim jejum","fast end","fasting end")
        if(source=="fitdays" && (dateIdx<0 || weightIdx<0))throw IllegalArgumentException("CSV Fitdays: não reconheci as colunas de data e peso. Envie o arquivo para adaptação.")
        if(source=="nodiet" && (startIdx<0 || endIdx<0))throw IllegalArgumentException("CSV No Diet: não reconheci as colunas início e fim. Envie a exportação real para adaptação.")
        var imported=0;var ignored=0
        for((index,line) in lines.drop(1).withIndex()){
            val cols=csvParts(line,sep)
            fun v(idx:Int)= if(idx in cols.indices)cols[idx] else ""
            try {
                if(source=="fitdays"){
                    val stamp=parseLocal(v(dateIdx))
                    if(stamp==null){ignored++;continue}
                    val weight=number(v(weightIdx))
                    if(weight==null){ignored++;continue}
                    if(weight !in 20.0..400.0){ignored++;continue}
                    val details=JSONObject().put("timestamp",stamp.toString()).put("origin","fitdays_csv")
                    val id=UUID.nameUUIDFromBytes("fitdays:${stamp}:$weight".toByteArray()).toString()
                    if(db.add(profile,"weight",stamp.toLocalDate().toString(),weight,details,"fitdays-csv",id))imported++else ignored++
                    val fat=number(v(fatIdx));val muscle=number(v(muscleIdx))
                    if(fat!=null||muscle!=null){
                        val d=JSONObject().put("timestamp",stamp.toString())
                        if(fat!=null)d.put("bodyFatPercent",fat)
                        if(muscle!=null)d.put("muscleMassKg",muscle)
                        val measureId=UUID.nameUUIDFromBytes("fitdays-measure:${stamp}:$fat:$muscle".toByteArray()).toString()
                        if(db.add(profile,"measure",stamp.toLocalDate().toString(),null,d,"fitdays-csv",measureId))imported++else ignored++
                    }
                } else {
                    val start=parseLocal(v(startIdx))
                    val end=parseLocal(v(endIdx))
                    if(start==null||end==null){ignored++;continue}
                    if(!end.isAfter(start)||Duration.between(start,end).toDays()>7){ignored++;continue}
                    val d=JSONObject().put("start",start.toString()).put("end",end.toString()).put("importedFrom","No Diet CSV (estrutura genérica)")
                    val id=UUID.nameUUIDFromBytes("nodiet:${start}:${end}".toByteArray()).toString()
                    if(db.add(profile,"fast",start.toLocalDate().toString(),Duration.between(start,end).toMinutes().toDouble(),d,"nodiet-csv",id))imported++else ignored++
                }
            } catch (_:Exception) {ignored++}
        }
        return imported to ignored
    }
}
