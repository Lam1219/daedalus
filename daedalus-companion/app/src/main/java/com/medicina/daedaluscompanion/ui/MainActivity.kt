package com.medicina.daedaluscompanion.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.auth.AuthError
import com.medicina.daedaluscompanion.auth.AuthResult
import com.medicina.daedaluscompanion.auth.AuthState
import com.medicina.daedaluscompanion.utils.DaedalusApplication
import kotlinx.coroutines.launch
import com.medicina.daedaluscompanion.data.MedReminders
import com.medicina.daedaluscompanion.ui.screens.*

/** AppCompatActivity so the in-app language switch (AppCompatDelegate) works below Android 13. */
class MainActivity : AppCompatActivity() {
    /** Error from the last email link (expired confirmation / reset), shown on the login screen. */
    private var callbackError by mutableStateOf<AuthError?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = (application as DaedalusApplication).auth
        // Keep dose reminder alarms in step with the schedule.
        MedReminders.reschedule(this)
        lifecycleScope.launch { auth.refreshIfNeeded() }
        handleAuthCallback(intent)
        val openMeds = intent?.getStringExtra(MedReminders.EXTRA_OPEN_TAB) == "meds"
        setContent {
            DaedalusTheme {
                when (val state = auth.state.collectAsState().value) {
                    AuthState.Loading -> Unit
                    AuthState.SignedOut -> LoginScreen(auth, callbackError)
                    AuthState.SettingNewPassword -> NewPasswordScreen(auth)
                    // First sign-in on this account: personal information before the app.
                    is AuthState.SignedIn -> ProfileSetupGate(auth, state.email) { DaedalusApp(startOnMeds = openMeds) }
                    AuthState.Guest -> DaedalusApp(startOnMeds = openMeds)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthCallback(intent)
    }

    /** daedalus://auth-callback#… from a confirmation or password-reset email. */
    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "daedalus" || uri.host != "auth-callback") return
        val auth = (application as DaedalusApplication).auth
        lifecycleScope.launch {
            val result = auth.handleCallback(uri)
            callbackError = (result as? AuthResult.Failure)?.error
        }
    }
}

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    TODAY(R.string.nav_today, Icons.Outlined.Home),
    CUES(R.string.nav_cues, Icons.Outlined.GraphicEq),
    JOURNAL(R.string.nav_journal, Icons.Outlined.CalendarMonth),
    MEDS(R.string.nav_meds, Icons.Outlined.Medication),
    PROFILE(R.string.nav_profile, Icons.Outlined.Person),
}

private enum class SubScreen { NONE, CALIBRATION, SESSIONS, SCHEDULE, DATA }

@Composable
fun DaedalusApp(startOnMeds: Boolean = false) {
    var tab by rememberSaveable { mutableStateOf(if (startOnMeds) Tab.MEDS else Tab.TODAY) }
    var sub by rememberSaveable { mutableStateOf(SubScreen.NONE) }

    BackHandler(enabled = sub != SubScreen.NONE) { sub = SubScreen.NONE }

    Scaffold(
        containerColor = DesignTokens.Ground,
        bottomBar = {
            NavigationBar(containerColor = DesignTokens.Surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; sub = SubScreen.NONE },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = {
                            Text(
                                stringResource(t.label),
                                fontSize = 11.sp,
                                fontWeight = if (tab == t) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DesignTokens.SageDeep,
                            selectedTextColor = DesignTokens.SageDeep,
                            indicatorColor = DesignTokens.SageTint,
                            unselectedIconColor = DesignTokens.NavInactive,
                            unselectedTextColor = DesignTokens.NavInactive,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                sub == SubScreen.CALIBRATION -> CalibrationScreen(onBack = { sub = SubScreen.NONE })
                sub == SubScreen.SESSIONS -> SessionsScreen(onBack = { sub = SubScreen.NONE })
                sub == SubScreen.SCHEDULE -> MedScheduleScreen(onBack = { sub = SubScreen.NONE })
                sub == SubScreen.DATA -> DataScreen(onBack = { sub = SubScreen.NONE })
                else -> when (tab) {
                    Tab.TODAY -> HomeScreen(
                        onOpenCues = { tab = Tab.CUES },
                        onOpenMeds = { tab = Tab.MEDS },
                        onOpenProfile = { tab = Tab.PROFILE },
                    )
                    Tab.CUES -> CuesScreen()
                    Tab.JOURNAL -> JournalScreen()
                    Tab.MEDS -> MedicationScreen()
                    Tab.PROFILE -> ProfileScreen(
                        onOpenCalibration = { sub = SubScreen.CALIBRATION },
                        onOpenSessions = { sub = SubScreen.SESSIONS },
                        onOpenSchedule = { sub = SubScreen.SCHEDULE },
                        onOpenData = { sub = SubScreen.DATA },
                    )
                }
            }
        }
    }
}
