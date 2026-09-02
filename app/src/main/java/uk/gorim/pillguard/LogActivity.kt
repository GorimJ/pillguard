package uk.gorim.pillguard

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.history)
        val list = ListView(this)
        val entries = Store.get(this).log.asReversed().map { "${TimeFmt.dayHm(it.atMillis)}  —  ${it.text}" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, if (entries.isEmpty()) listOf("Nothing yet.") else entries)
        setContentView(list)
    }
}
