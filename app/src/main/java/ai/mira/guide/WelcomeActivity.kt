package ai.mira.guide

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity

/** One-screen first run: what Mira is + language choice. Shown once. */
class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val hindi = findViewById<RadioButton>(R.id.rbLangHi).isChecked
            getSharedPreferences("mira", MODE_PRIVATE).edit()
                .putBoolean("seen", true)
                .putString("lang", if (hindi) "hi" else "en")
                .apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
