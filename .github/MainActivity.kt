package br.com.ritmo.nativo

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.permission.PermissionController
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** RITMO Android 2.0: sem WebView, sem HTML e sem servidores de dados. */
class MainActivity : ComponentActivity() {
    private val bg=Color.rgb(10,18,31)
    private val panel=Color.rgb(23,35,54)
    private val fg=Color.rgb(236,245,250)
    private val muted=Color.rgb(159,177,193)
    private val accent=Color.rgb(55,219,171)
    private val cyan=Color.rgb(117,206,245)
    private lateinit var db:RitmoDb
    private lateinit var outer:LinearLayout
    private var activeProfile=""
    private var page="Visão geral"
    private var importMode="ritmo"
    private var timerView:TextView?=null
    private val handler=Handler(Looper.getMainLooper())
    private val timerTicker=object:Runnable {
        override fun run(){ updateTimerLabel();handler.postDelayed(this,30_000) }
    }
    private val pickFile=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->
        if(uri!=null) try{
            val bytes=contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("Arquivo vazio")
            if(bytes.size>3_000_000)error("Arquivo grande demais (máximo 3 MB)")
            val text=bytes.toString(Charsets.UTF_8)
            val result=if(importMode=="ritmo")db.importJson(activeProfile,text)
                else ExternalImport.importCsv(db,activeProfile,text,importMode)
            message("Importação: ${result.first} registros adicionados, ${result.second} ignorados (incluindo duplicados).")
            render()
        } catch(e:Exception){message("Importação não concluída: ${e.message}")}
    }
    private val saveFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri:Uri?->
        if(uri!=null)try{
            contentResolver.openOutputStream(uri)?.use{out->out.write(db.exportJson(activeProfile).toString(2).toByteArray())}
            message("Backup do perfil exportado.")
        }catch(e:Exception){message("Erro no backup: ${e.message}")}
    }
    private val healthLauncher=registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if(granted.isNotEmpty())importHealth() else message("Health Connect: permissão não concedida.")
    }
    private val job=SupervisorJob()
    private val scope=CoroutineScope(job+Dispatchers.Main)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        db=RitmoDb(this)
        activeProfile=getPreferences(MODE_PRIVATE).getString("profile","")?:""
        if(db.profiles().none{it.first==activeProfile})activeProfile=""
        render()
        handler.post(timerTicker)
    }
    override fun onDestroy(){handler.removeCallbacks(timerTicker);scope.cancel();db.close();super.onDestroy()}

    private fun dp(v:Int)=(resources.displayMetrics.density*v).roundToInt()
    private fun shape(color:Int,radius:Int=18):GradientDrawable=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun txt(text:String,size:Int=15,color:Int=fg,bold:Boolean=false):TextView=TextView(this).apply {
        this.text=text;setTextColor(color);textSize=size.toFloat()
        if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun column():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun pad(view:View,h:Int=14,v:Int=14):View=view.apply{setPadding(dp(h),dp(v),dp(h),dp(v))}
    private fun gap(size:Int=10):View=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(size))}
    private fun btn(label:String,onClick:()->Unit):TextView=txt(label,14,bg,true).apply{
        gravity=Gravity.CENTER;background=shape(accent,13);setPadding(dp(14),dp(12),dp(14),dp(12))
        isClickable=true;setOnClickListener{onClick()}
    }
    private fun ghost(label:String,onClick:()->Unit):TextView=txt(label,14,fg,true).apply{
        gravity=Gravity.CENTER;background=shape(Color.rgb(40,57,78),13)
        setPadding(dp(14),dp(11),dp(14),dp(11));setOnClickListener{onClick()}
    }
    private fun card(title:String,content:LinearLayout.()->Unit):View=column().apply{
        background=shape(panel);setPadding(dp(16),dp(16),dp(16),dp(16))
        addView(txt(title,17,fg,true));addView(gap(9));content()
    }
    private fun line(a:String,b:String):View=row().apply{
        addView(txt(a,13,muted),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        addView(txt(b,14,fg,true))
    }
    private fun container(content:LinearLayout.()->Unit):View=column().apply{
        setPadding(dp(14),dp(12),dp(14),dp(30));content()
    }
    private fun LinearLayout.put(v:View){addView(v);addView(gap(12))}
    private fun mini(title:String,large:String):View=column().apply{
        background=shape(panel,14);setPadding(dp(13),dp(13),dp(13),dp(13))
        addView(txt(title,12,muted));addView(gap(5));addView(txt(large,22,fg,true))
    }
    private fun message(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private fun navigate(target:String){page=target;timerView=null;render()}
    private fun render(){
        outer=column().apply{setBackgroundColor(bg)}
        setContentView(outer)
        outer.addView(row().apply{
            setPadding(dp(17),dp(18),dp(17),dp(12))
            addView(txt("◉  RITMO",23,accent,true),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            if(activeProfile.isNotBlank())addView(ghost(db.profileName(activeProfile)+"  ▾"){chooseProfile()})
        })
        if(activeProfile.isBlank()){profileIntro();return}
        val bar=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        bar.addView(row().apply{
            setPadding(dp(12),dp(3),dp(12),dp(7))
            listOf("Visão geral","Calendário","Jejum","Mounjaro","Medidas","Integrações").forEach {name->
                addView(ghost(if(page==name) "● $name" else name){navigate(name)})
                addView(View(this@MainActivity).apply{layoutParams=LinearLayout.LayoutParams(dp(7),1)})
            }
        })
        outer.addView(bar)
        val scrolling=ScrollView(this).apply{fillViewport=true}
        val content=container {
            when(page){
                "Visão geral"->dashboard(this)
                "Calendário"->calendarScreen(this)
                "Jejum"->fastScreen(this)
                "Mounjaro"->medScreen(this)
                "Medidas"->measurementScreen(this)
                else->integrationsScreen(this)
            }
        }
        scrolling.addView(content);outer.addView(scrolling,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun profileIntro(){
        outer.addView(container {
            put(card("Comece por um perfil") {
                put(txt("Cada pessoa tem registros separados neste aparelho. O RITMO não cria contas em nuvem e não compartilha automaticamente dados entre celulares.",15,muted))
                put(btn("Criar meu perfil"){createProfile()})
            })
            db.profiles().forEach{ p->put(ghost("Abrir ${p.second}"){setProfile(p.first)}) }
        })
    }
    private fun setProfile(id:String){activeProfile=id;getPreferences(MODE_PRIVATE).edit().putString("profile",id).apply();page="Visão geral";render()}
    private fun createProfile(){
        val f=EditText(this).apply{hint="Nome do perfil";setSingleLine(true)}
        AlertDialog.Builder(this).setTitle("Novo perfil no aparelho").setView(pad(f))
            .setPositiveButton("Criar"){_,_->setProfile(db.createProfile(f.text.toString()))}
            .setNegativeButton("Cancelar",null).show()
    }
    private fun chooseProfile(){
        val profiles=db.profiles()
        val labels=profiles.map{it.second}.plus("+ Criar novo perfil")
        AlertDialog.Builder(this).setTitle("Perfil neste dispositivo")
            .setItems(labels.toTypedArray()){_,which->if(which==profiles.size)createProfile()else setProfile(profiles[which].first)}
            .show()
    }
    private fun decimal(d:Double?,places:Int=1):String=if(d==null)"—" else String.format(Locale("pt","BR"),"%.${places}f",d)
    private fun dashboard(layout:LinearLayout){
        val weights=db.records(activeProfile,"weight").filter{it.has("value")}.sortedBy{it.optString("date")}
        val first=weights.firstOrNull()?.optDouble("value");val last=weights.lastOrNull()?.optDouble("value")
        val loss=if(first!=null&&last!=null)first-last else null
        val percent=if(first!=null&&last!=null&&first>0) (first-last)*100.0/first else null
        layout.put(txt("Sua jornada, no seu ritmo.",21,fg,true))
        val metrics=row()
        metrics.addView(mini("Peso atual",if(last==null)"—" else "${decimal(last)} kg"),LinearLayout.LayoutParams(0,-2,1f))
        metrics.addView(View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(8),1)})
        metrics.addView(mini("Variação",if(loss==null)"—" else "${if(loss>=0)"−" else "+"}${decimal(kotlin.math.abs(loss))} kg"),LinearLayout.LayoutParams(0,-2,1f))
        layout.put(metrics)
        layout.put(card("Percentual desde a primeira pesagem") {
            addView(txt(if(percent==null)"Registre seu peso" else "${if(percent>=0)"−" else "+"}${decimal(kotlin.math.abs(percent),2)}%",31,accent,true))
            addView(txt("Calculado sobre o peso inicial; pode variar conforme seus registros.",12,muted))
        })
        layout.put(card("Tendência do peso") {
            if(weights.size<2)addView(txt("Importe seu histórico para visualizar a curva.",14,muted))
            else addView(WeightChart(this@MainActivity,weights.map{it.getDouble("value")},fg,accent).apply{layoutParams=LinearLayout.LayoutParams(-1,dp(185))})
        })
        val all=db.records(activeProfile)
        val xp=all.sumOf { when(it.optString("kind")){"weight"->5;"measure"->5;"medication"->5;"fast"->3;"habit"->2;else->1} }
        val uniqueDays=all.map{it.optString("date")}.distinct().size
        layout.put(card("Conquistas e rotina") {
            put(line("Nível", "${1+xp/100}"))
            put(line("Experiência", "$xp XP"))
            put(line("Dias acompanhados", "$uniqueDays"))
            put(txt("Os pontos recompensam registros e autocuidado, nunca jejum prolongado, doses ou velocidade de emagrecimento.",12,muted))
        })
        layout.put(btn("+ Registrar peso") {form("Nova pesagem",listOf("Data (AAAA-MM-DD)","Peso (kg)","Observação"),listOf(today(),"","")){v->
            val n=toDouble(v[1])?:error("Peso inválido")
            require(n in 20.0..400.0){"Peso fora do intervalo aceito"}
            db.add(activeProfile,"weight",validDate(v[0]),n,JSONObject().put("note",v[2]));render()
        }})
        layout.put(btn("+ Hábitos e bem-estar") {form("Registro de bem-estar",listOf("Data (AAAA-MM-DD)","Água (mL)","Atividade (min)","Sono (h)","Notas/sintomas"),listOf(today(),"","","","")){v->
            val d=JSONObject().put("waterMl",toDouble(v[1])).put("activityMinutes",toDouble(v[2])).put("sleepHours",toDouble(v[3])).put("notes",v[4]);db.add(activeProfile,"habit",validDate(v[0]),null,d);render()
        }})
        layout.put(ghost("Ver eventos e histórico"){navigate("Calendário")})
    }
    private fun calendarScreen(layout:LinearLayout){
        layout.put(txt("Calendário de acompanhamento",21,fg,true))
        val selected=txt("Eventos de ${today()}",16,accent,true)
        val events=column()
        val calendar=CalendarView(this).apply{
            setBackgroundColor(panel)
            setOnDateChangeListener { _,year,month,day->
                val date="%04d-%02d-%02d".format(Locale.US,year,month+1,day)
                selected.text="Eventos de $date";showDay(events,date)
            }
        }
        layout.put(card("Escolha um dia") {addView(calendar)})
        layout.put(selected);layout.put(events);showDay(events,today())
    }
    private fun showDay(view:LinearLayout,date:String){
        view.removeAllViews()
        val list=db.records(activeProfile).filter{it.optString("date")==date}
        if(list.isEmpty())view.addView(txt("Nenhum registro nesta data.",14,muted))
        for(r in list){
            val kind=r.optString("kind");val desc=when(kind){
                "weight"->"Peso: ${decimal(r.optDouble("value"))} kg"
                "medication"->"Aplicação registrada: ${decimal(r.optDouble("value"))} mg"
                "fast"->"Jejum: ${decimal(r.optDouble("value")/60.0)} h"
                "measure"->"Medidas corporais"
                "habit"->"Hábitos e bem-estar"
                else->"Anotação"
            }
            view.addView(txt("• $desc  ·  ${r.optString("source")}",14,fg))
            view.addView(gap(8))
        }
    }
    private fun fastScreen(layout:LinearLayout){
        layout.put(txt("Relógio de jejum",21,fg,true))
        val active=db.setting(activeProfile,"active_fast")
        layout.put(card("Sessão atual"){
            timerView=txt("",34,accent,true);addView(timerView);addView(gap(10));updateTimerLabel()
            if(active.isBlank())put(btn("Iniciar jejum"){
                db.setSetting(activeProfile,"active_fast",Instant.now().toString());render()
            })else put(btn("Encerrar e registrar"){
                try{
                    val start=Instant.parse(active);val end=Instant.now();val mins=Duration.between(start,end).toMinutes()
                    require(mins in 1..10080){"Duração fora do limite de registro"}
                    val d=JSONObject().put("start",start.toString()).put("end",end.toString()).put("origin","ritmo-native")
                    db.add(activeProfile,"fast",start.atZone(ZoneId.systemDefault()).toLocalDate().toString(),mins.toDouble(),d)
                    db.setSetting(activeProfile,"active_fast","");render()
                }catch(e:Exception){message("Não foi possível concluir: ${e.message}")}
            })
        })
        layout.put(card("Registro consciente") {
            addView(txt("O relógio apenas registra horários: não recomenda duração, não certifica 'queima de gordura' e não premia jejuns mais longos.",14,muted))
            addView(gap(6))
            addView(txt("Em tratamento com tirzepatida, especialmente se houver náusea, vômitos ou dificuldade de hidratação, discuta períodos de jejum com seu médico.",13,muted))
        })
        layout.put(ghost("Importar sessões do No Diet (CSV)") {chooseImport("nodiet")})
        db.records(activeProfile,"fast").asReversed().take(20).forEach {r->
            layout.put(card(r.optString("date")) {addView(txt("${decimal(r.optDouble("value")/60.0)} horas  ·  ${r.optString("source")}",15,fg))})
        }
    }
    private fun updateTimerLabel(){
        val t=timerView?:return
        if(activeProfile.isBlank())return
        val start=db.setting(activeProfile,"active_fast")
        if(start.isBlank()) t.text="00:00" else try{
            val s=Duration.between(Instant.parse(start),Instant.now()).seconds.coerceAtLeast(0)
            t.text="%02d:%02d:%02d".format(Locale.US,s/3600,(s%3600)/60,s%60)
        }catch(_:Exception){t.text="—"}
    }
    private fun medScreen(layout:LinearLayout){
        layout.put(txt("Registro de aplicações",21,fg,true))
        layout.put(card("Mounjaro · tirzepatida") {
            addView(txt("Registre somente aplicações realmente realizadas. Alterações de fase ou de prescrição não serão transformadas em doses administradas.",14,muted))
        })
        layout.put(btn("+ Registrar aplicação"){
            form("Aplicação realizada",listOf("Data (AAAA-MM-DD)","Dose aplicada (mg)","Local da aplicação", "Lote (opcional)","Sintomas e observações"),listOf(today(),"","","","")){v->
                val dose=toDouble(v[1])?:error("Informe a dose efetivamente aplicada")
                require(dose>0 && dose<=50){"Dose inválida"}
                val d=JSONObject().put("site",v[2]).put("lot",v[3]).put("note",v[4])
                db.add(activeProfile,"medication",validDate(v[0]),dose,d);render()
            }
        })
        val meds=db.records(activeProfile,"medication")
        if(meds.isEmpty())layout.put(txt("Nenhuma aplicação individual registrada.",14,muted))
        meds.asReversed().take(40).forEach{r->
            layout.put(card(r.optString("date")) {
                addView(txt("${decimal(r.optDouble("value"))} mg",19,accent,true))
                addView(txt(r.optJSONObject("details")?.optString("note")?:"",13,muted))
            })
        }
    }
    private fun measurementScreen(layout:LinearLayout){
        layout.put(txt("Medidas e composição",21,fg,true))
        layout.put(btn("+ Registrar medidas"){
            form("Medidas corporais",listOf("Data (AAAA-MM-DD)","Cintura (cm)","Abdômen (cm)","Quadril (cm)","Peito (cm)","Braço (cm)","Coxa (cm)"),listOf(today(),"","","","","","")){v->
                val keys=listOf("waistCm","abdomenCm","hipCm","chestCm","armCm","thighCm")
                val d=JSONObject();keys.forEachIndexed{i,key-> toDouble(v[i+1])?.let{d.put(key,it)} }
                require(d.length()>0){"Informe ao menos uma medida"}
                db.add(activeProfile,"measure",validDate(v[0]),null,d);render()
            }
        })
        db.records(activeProfile,"measure").asReversed().take(35).forEach{r->
            val d=r.optJSONObject("details")?:JSONObject()
            val parts=mutableListOf<String>()
            for((key,label) in listOf("waistCm" to "Cintura", "abdomenCm" to "Abdômen","hipCm" to "Quadril","chestCm" to "Peito","armCm" to "Braço","thighCm" to "Coxa","bodyFatPercent" to "Gordura (%)","muscleMassKg" to "Músculos (kg)","leanBodyMassKg" to "Massa magra (kg)","bodyWaterMassKg" to "Água corporal (kg)")){
                if(d.has(key))parts.add("$label: ${decimal(d.optDouble(key))}")
            }
            layout.put(card(r.optString("date")) {
                addView(txt(parts.joinToString("  ·  ").ifBlank {"Registro de medidas"},14,fg))
                addView(txt(r.optString("source"),12,muted))
            })
        }
        if(db.records(activeProfile,"measure").isEmpty())layout.put(txt("Ainda não há medidas registradas.",14,muted))
    }
    private fun integrationsScreen(layout:LinearLayout){
        layout.put(txt("Integrações e dados",21,fg,true))
        layout.put(card("Fitdays → Health Connect") {
            put(txt("Quando os registros estiverem disponíveis no Health Connect e você autorizar, o RITMO importa peso e composição corporal. O Fitdays pode oferecer apenas Google Fit em algumas versões; nesse caso utilize CSV.",14,muted))
            put(btn("Conectar e sincronizar Health Connect"){requestHealth()})
            put(ghost("Importar CSV do Fitdays"){chooseImport("fitdays")})
        })
        layout.put(card("No Diet · Fasting Tracker") {
            put(txt("Não há acesso ao seu histórico pelo link de instalação. O importador recebe CSV com colunas de início e fim; o formato real de exportação do No Diet ainda precisa ser validado.",14,muted))
            put(ghost("Selecionar exportação de jejum (CSV)"){chooseImport("nodiet")})
        })
        layout.put(card("Seus dados, seu controle") {
            put(ghost("Importar histórico RITMO (.json)"){chooseImport("ritmo")})
            put(ghost("Exportar backup do perfil (.json)"){saveFile.launch("ritmo-${db.profileName(activeProfile).replace(' ','-')}-${today()}.json")})
            put(txt("Os perfis permanecem separados neste aparelho. Backup manual não sincroniza celulares e não constitui autenticação de contas remotas.",13,muted))
        })
        layout.put(card("Privacidade e permissões") {
            put(txt("Sem publicidade, sem analytics e sem servidor. A base SQLite permanece no armazenamento privado do app. Não use em dispositivo desbloqueado compartilhado sem proteção do sistema. O backup JSON não é criptografado.",13,muted))
        })
    }
    private fun requestHealth(){
        val hc=HealthSync(this)
        if(!hc.available()){message("Health Connect indisponível. Use a importação CSV do Fitdays.");return}
        val bound=getPreferences(MODE_PRIVATE).getString("health_profile",null)
        if(bound!=null && bound!=activeProfile){
            message("Health Connect já foi vinculado a outro perfil deste celular. Evitamos misturar medições de pessoas diferentes.");return
        }
        if(bound==null){
            AlertDialog.Builder(this).setTitle("Vincular Health Connect a este perfil?")
                .setMessage("Os dados compartilhados pelo Android serão importados exclusivamente para '${db.profileName(activeProfile)}'. Outros perfis não poderão importar esses dados no mesmo aparelho sem redefinir o vínculo. Prosseguir?")
                .setPositiveButton("Vincular"){_,_->
                    getPreferences(MODE_PRIVATE).edit().putString("health_profile",activeProfile).apply()
                    healthLauncher.launch(hc.permissions)
                }.setNegativeButton("Cancelar",null).show()
        }else healthLauncher.launch(hc.permissions)
    }
    private fun importHealth(){
        if(getPreferences(MODE_PRIVATE).getString("health_profile",null)!=activeProfile){message("Perfil Health Connect diferente");return}
        scope.launch{
            try{
                val result=withContext(Dispatchers.IO){HealthSync(this@MainActivity).importAuthorized(db,activeProfile)}
                message("Health Connect: ${result.first} registros, ${result.second} duplicados. Apenas dados de fontes autorizadas e disponíveis.")
                render()
            }catch(e:Exception){message("Health Connect: ${e.message}")}
        }
    }
    private fun chooseImport(mode:String){
        importMode=mode
        val types=if(mode=="ritmo")arrayOf("application/json","text/plain","*/*") else arrayOf("text/csv","text/comma-separated-values","text/plain","*/*")
        pickFile.launch(types)
    }
    private fun form(title:String,labels:List<String>,defaults:List<String>,onSave:(List<String>)->Unit){
        val area=column().apply{setPadding(dp(22),dp(8),dp(22),dp(10))}
        val inputs=labels.mapIndexed{i,label->
            val edit=EditText(this).apply{
                hint=label;setText(defaults.getOrElse(i){""})
                setTextColor(fg);setHintTextColor(muted);textSize=14f
                if(label.contains("kg")||label.contains("mg")||label.contains("cm")||label.contains("mL")||label.contains("min")||label.contains("(h)")){
                    inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                }
            };area.addView(edit);edit
        }
        val scrolling=ScrollView(this).apply{addView(area)}
        val dlg=AlertDialog.Builder(this).setTitle(title).setView(scrolling)
            .setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create()
        dlg.setOnShowListener{
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                try{onSave(inputs.map{it.text.toString()});dlg.dismiss()}catch(e:Exception){
                    message("Verifique os campos: ${e.message}")
                }
            }
        }
        dlg.show()
    }
    private fun validDate(s:String):String=LocalDate.parse(s.trim()).toString()
    private fun toDouble(s:String):Double?=s.trim().replace(',','.').toDoubleOrNull()?.takeIf{it.isFinite()}
    private fun today():String=LocalDate.now().toString()

    private class WeightChart(ctx:Context, private val values:List<Double>, private val ink:Int, private val accent:Int):View(ctx){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){
            super.onDraw(c);if(values.size<2)return
            val left=35f;val top=22f;val right=width-15f;val bottom=height-27f
            val low=values.minOrNull()?:0.0;val high=values.maxOrNull()?:1.0
            val range=(high-low).coerceAtLeast(1.0)
            p.strokeWidth=1.5f;p.color=Color.rgb(77,100,119)
            for(i in 0..3){val y=top+(bottom-top)*i/3f;c.drawLine(left,y,right,y,p)}
            p.color=accent;p.strokeWidth=dpView(context,3).toFloat();p.style=Paint.Style.STROKE
            val path=android.graphics.Path()
            values.forEachIndexed { index, value ->
                val x=left+(right-left)*index/(values.size-1).toFloat()
                val y=bottom-((value-low)/range*(bottom-top)).toFloat()
                if(index==0)path.moveTo(x,y)else path.lineTo(x,y)
            }
            c.drawPath(path,p);p.style=Paint.Style.FILL;p.textSize=dpView(context,11).toFloat()
            p.color=ink;c.drawText(String.format(Locale.US,"%.1f",high),0f,top+4f,p)
            c.drawText(String.format(Locale.US,"%.1f",low),0f,bottom,p)
        }
        private fun dpView(ctx:Context,v:Int)=(ctx.resources.displayMetrics.density*v).roundToInt()
    }
}
