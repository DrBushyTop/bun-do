package fi.bundo.identity

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.bundo.BunDoApplication
import fi.bundo.MainActivity
import fi.bundo.ui.AccountScreen
import fi.bundo.ui.BunDoTheme

class SignInActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: SignInModel = viewModel()
            BunDoTheme("system") {
                AccountScreen((application as BunDoApplication).accounts, model) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }
    }
}
