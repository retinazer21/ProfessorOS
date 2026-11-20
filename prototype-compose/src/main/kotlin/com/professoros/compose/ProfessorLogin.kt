package com.professoros.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.professoros.service.PortalPublishingClient
import kotlinx.coroutines.*

@Composable
internal fun ProfessorLogin(busy: Boolean, error: String?, submit: (String,String,String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Ink.background), contentAlignment=Alignment.Center) {
        Column(Modifier.widthIn(max=436.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Image(painterResource("icons/professor-os-64.png"),null,Modifier.size(40.dp))
                Copy("Professor OS",20,weight=FontWeight.SemiBold)
            }
            Panel(Modifier.fillMaxWidth()) {
                Copy("Professor sign-in",27,weight=FontWeight.SemiBold)
                CompositionLocalProvider(LocalControlsEnabled provides !busy) {
                    Input("University email",email,{email=it})
                    Input("Password",password,{password=it},secret=true)
                    Input("Authenticator code",code,{code=it.filter(Char::isDigit).take(6)},secret=true)
                    error?.let {Copy(it,13,LocalPalette.current.danger)}
                    Action(if(busy) "Verifying…" else "Open workspace",variant=ActionVariant.PRIMARY,
                        enabled=email.isNotBlank() && password.isNotEmpty() && code.length==6) {
                        submit(email,password,code)
                        password=""; code=""
                    }
                }
            }
        }
    }
}
