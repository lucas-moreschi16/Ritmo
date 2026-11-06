package br.com.ritmo.nativo

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val view = TextView(this).apply {
      text = "RITMO solicita acesso ao peso e à composição corporal no Health Connect apenas para importar medições autorizadas. Os dados são gravados localmente no perfil selecionado. Você pode revogar o acesso nas configurações do Android. Não utilizamos estes dados para publicidade ou envio a servidores."
      textSize = 18f
      setPadding(40, 70, 40, 40)
    }
    setContentView(LinearLayout(this).apply { addView(view) })
  }
}
