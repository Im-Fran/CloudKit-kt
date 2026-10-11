package io.github.imfran.cloudkit.sample

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.imfran.cloudkit.Auth
import io.github.imfran.cloudkit.CloudKit
import io.github.imfran.cloudkit.Container
import io.github.imfran.cloudkit.ContainerConfig
import io.github.imfran.cloudkit.Environment
import io.github.imfran.cloudkit.android.CloudKitSignIn
import io.github.imfran.cloudkit.android.PreferencesStore
import io.github.imfran.cloudkit.model.Query
import io.github.imfran.cloudkit.model.Record
import io.github.imfran.cloudkit.model.Sort
import io.github.imfran.cloudkit.model.record
import io.github.imfran.cloudkit.model.string
import kotlinx.coroutines.launch

class SampleApp : Application() {
    lateinit var container: Container

    override fun onCreate() {
        super.onCreate()
        container = CloudKit.configure(
            ContainerConfig(
                containerIdentifier = BuildConfig.CLOUDKIT_CONTAINER,
                environment = Environment.DEVELOPMENT,
                auth = Auth.ApiToken(BuildConfig.CLOUDKIT_API_TOKEN, PreferencesStore(this)),
            ),
        ).getDefaultContainer()
        CloudKitSignIn.register(container)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as SampleApp).container
        setContent { MaterialTheme { NotesScreen(this, container) } }
    }
}

// Expects a "Note" record type with a queryable/sortable "title" String field in a private database.
@Composable
private fun NotesScreen(activity: ComponentActivity, container: Container) {
    val scope = rememberCoroutineScope()
    val user by container.userIdentity.collectAsState()
    var notes by remember { mutableStateOf(emptyList<Record>()) }
    var title by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    fun run(block: suspend () -> Unit) = scope.launch {
        status = "…"
        status = runCatching { block() }.fold({ "" }, { it.message ?: it.toString() })
    }

    val db = container.privateCloudDatabase
    val query = Query("Note", sortBy = listOf(Sort(fieldName = "title")))

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(user?.let { "Signed in: ${it.userRecordName}" } ?: "Signed out")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { run { CloudKitSignIn.signIn(activity, container) } }) { Text("Sign in") }
            Button(onClick = { run { notes = db.performQuery(query).records } }) { Text("Load") }
            Button(onClick = { container.signOut() }) { Text("Sign out") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.weight(1f), label = { Text("Title") })
            Button(onClick = {
                run {
                    db.saveRecord(record("Note") { this["title"] = title })
                    title = ""
                    notes = db.performQuery(query).records
                }
            }) { Text("Add") }
        }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.error)
        LazyColumn { items(notes) { Text(it.string("title").orEmpty(), Modifier.padding(vertical = 4.dp)) } }
    }
}
