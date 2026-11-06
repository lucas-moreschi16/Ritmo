package br.com.ritmo.nativo

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Ponte Android nativa: importa apenas dados já publicados no Health Connect com consentimento. */
class HealthSync(private val context:Context) {
    val permissions = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class)
    )
    fun available()= HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE
    fun client():HealthConnectClient=HealthConnectClient.getOrCreate(context)
    suspend fun importAuthorized(db:RitmoDb,profile:String):Pair<Int,Int>{
        val c=client();val granted=c.permissionController.getGrantedPermissions()
        if(granted.intersect(permissions).isEmpty()) throw IllegalStateException("Acesso ao Health Connect não autorizado")
        // Por padrão, 29 dias (histórico mais antigo exige permissão adicional específica).
        val filter=TimeRangeFilter.after(Instant.now().minusSeconds(29L*86400L))
        var count=0;var duplicates=0
        suspend fun <T:androidx.health.connect.client.records.Record> pageThrough(
            recordType:kotlin.reflect.KClass<T>, handler:(T)->Unit
        ) {
            var token:String?=null
            do {
                val reply=c.readRecords(ReadRecordsRequest(recordType=recordType,timeRangeFilter=filter,pageSize=500,pageToken=token))
                reply.records.forEach(handler)
                token=reply.pageToken
            }while(token!=null)
        }
        if(HealthPermission.getReadPermission(WeightRecord::class) in granted) {
            pageThrough(WeightRecord::class) { record ->
                val date=record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val d=JSONObject().put("originPackage",record.metadata.dataOrigin.packageName).put("instant",record.time.toString())
                val ok=db.add(profile,"weight",date,record.weight.inKilograms,d,"health-connect","hc:weight:${record.metadata.id}")
                if(ok) count++ else duplicates++
            }
        }
        if(HealthPermission.getReadPermission(BodyFatRecord::class) in granted) {
            pageThrough(BodyFatRecord::class) {record ->
                val date=record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val d=JSONObject().put("bodyFatPercent",record.percentage.value).put("originPackage",record.metadata.dataOrigin.packageName)
                if(db.add(profile,"measure",date,null,d,"health-connect","hc:fat:${record.metadata.id}"))count++else duplicates++
            }
        }
        if(HealthPermission.getReadPermission(LeanBodyMassRecord::class) in granted) {
            pageThrough(LeanBodyMassRecord::class) {record ->
                val date=record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val d=JSONObject().put("leanBodyMassKg",record.mass.inKilograms).put("originPackage",record.metadata.dataOrigin.packageName)
                if(db.add(profile,"measure",date,null,d,"health-connect","hc:lean:${record.metadata.id}"))count++else duplicates++
            }
        }
        if(HealthPermission.getReadPermission(BodyWaterMassRecord::class) in granted) {
            pageThrough(BodyWaterMassRecord::class) {record ->
                val date=record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val d=JSONObject().put("bodyWaterMassKg",record.mass.inKilograms).put("originPackage",record.metadata.dataOrigin.packageName)
                if(db.add(profile,"measure",date,null,d,"health-connect","hc:water:${record.metadata.id}"))count++else duplicates++
            }
        }
        return count to duplicates
    }
}
