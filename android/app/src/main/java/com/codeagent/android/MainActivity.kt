package com.codeagent.android
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.codeagent.android.llm.ProviderConfigStore
import com.codeagent.android.ui.LiveAgentApp
import com.codeagent.android.ui.theme.AgentTheme

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val keys=SecureKeyStore(applicationContext)
  val configs=ProviderConfigStore(applicationContext)
  setContent { AgentTheme { Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.background) { LiveAgentApp(keys,configs) } } }
 }
}
