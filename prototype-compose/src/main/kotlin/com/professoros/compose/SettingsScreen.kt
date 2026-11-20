package com.professoros.compose

import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.professoros.model.ThemeMode
import com.professoros.service.ThemePreferences
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/** Appearance uses the existing atomic local preference file; academic storage is unchanged. */
class AppearanceSettings(val database:Path, private val scope:CoroutineScope) {
    val preferenceFile:Path = database.resolveSibling("appearance.properties")
    private val preferences = ThemePreferences(preferenceFile)
    private val initial = preferences.read()
    var mode by mutableStateOf(initial.mode()); private set
    var accent by mutableStateOf(initial.accent()); private set
    var reduced by mutableStateOf(initial.reduced()); private set
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    fun select(next:ThemeMode, tone:String=accent, lessMotion:Boolean=reduced) {
        if(saving || (next==mode && tone==accent && lessMotion==reduced))return
        saving=true;error=null
        scope.launch {
            try { withContext(Dispatchers.IO){preferences.save(ThemePreferences.Choice(next,tone,lessMotion))};mode=next;accent=tone;reduced=lessMotion }
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){
                Logger.getLogger("ComposeSettings").log(Level.WARNING,"Cannot save appearance",failure)
                error="Appearance could not be saved. Check that the data folder is writable."
            } finally {saving=false}
        }
    }
}

@Composable internal fun SettingsScreen(settings:AppearanceSettings, openTerms:()->Unit) {
    Column(Modifier.fillMaxWidth().testTag("settings-page"),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Copy("Appearance",20)
            Copy("Choose a theme for this device.",13,Ink.muted)
            Action("Follow system appearance",active=settings.mode==ThemeMode.SYSTEM,enabled=!settings.saving,modifier=Modifier.testTag("theme-SYSTEM")) { settings.select(ThemeMode.SYSTEM) }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                themeCatalog.forEach { (id,sample) ->
                    val mode=ThemeMode.valueOf(id)
                    val p=sample.palette
                    val active=settings.mode==mode
                    val shape=RoundedCornerShape(8.dp)
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    Column(Modifier.width(144.dp).background(p.panel,shape)
                        .border(if(active || focused) 2.dp else 1.dp,if(focused) p.text else if(active) Ink.violet else Ink.border,shape)
                        .testTag("theme-"+id).selectable(active,interactionSource=interaction,indication=null,enabled=!settings.saving,role=Role.RadioButton){settings.select(mode)}.padding(10.dp)) {
                        Row(verticalAlignment=androidx.compose.ui.Alignment.Bottom, horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                            listOf(p.violet to 8.dp, p.positive to 13.dp, p.violet to 18.dp).forEach { (color,height) ->
                                Box(Modifier.width(10.dp).height(height).background(color,RoundedCornerShape(2.dp)))
                            }
                            if (active) { Spacer(Modifier.weight(1f)); Mark("check",p.text,16) }
                        }
                        Spacer(Modifier.height(7.dp)); Copy(sample.name,13,p.text)
                    }
                }
            }
            Copy("Accent",14)
            Toolbar { listOf("accent" to "Theme default","info" to "Blue","positive" to "Green").forEach { (key,label) ->
                Action(label,active=settings.accent==key,modifier=Modifier.semantics { selected=settings.accent==key },enabled=!settings.saving){settings.select(settings.mode,key)}
            } }
            Action("Reduce motion",icon=if(settings.reduced) "check" else "arrow",active=settings.reduced,modifier=Modifier.semantics { selected=settings.reduced },enabled=!settings.saving){settings.select(settings.mode,lessMotion=!settings.reduced)}
            settings.error?.let {Copy(it,13,Ink.violet,modifier=Modifier.testTag("settings-error"))}
        }
        Panel(Modifier.fillMaxWidth()) {
            Copy("Local storage",20)
            Copy("Your academic workspace stays on this device.",13,Ink.muted)
            Copy("DATABASE",11,Ink.muted)
            SelectionContainer {Copy(settings.database.toAbsolutePath().normalize().toString(),13,modifier=Modifier.testTag("storage-path"))}
            Copy("APPEARANCE PREFERENCES",11,Ink.muted)
            SelectionContainer {Copy(settings.preferenceFile.toAbsolutePath().normalize().toString(),13)}
            Copy("Storage is configured at launch with PROFESSOR_OS_DATA_DIR; the default is the data folder. To use another location, close the application, back up your data and set that variable before restarting. This screen does not move or recreate your database.",13,Ink.muted)
        }
        Panel(Modifier.fillMaxWidth()) {
            Copy("Academic organization",20)
            Copy("Manage fall, spring and summer sessions from Terms. Courses and offerings remain available in the sidebar.",13,Ink.muted)
            Action("Manage terms","calendar",modifier=Modifier.testTag("settings-terms"),onClick=openTerms)
        }
    }
}
