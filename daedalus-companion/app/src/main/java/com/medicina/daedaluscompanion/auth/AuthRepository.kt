package com.medicina.daedaluscompanion.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.medicina.daedaluscompanion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

private const val TAG = "Auth"

sealed interface AuthState {
    /** Restoring a saved session. */
    data object Loading : AuthState
    data object SignedOut : AuthState
    /** Using the app on this phone only, without an account. */
    data object Guest : AuthState
    data class SignedIn(val email: String?, val method: String) : AuthState
    /** Opened from a password-reset email: choose a new password before continuing. */
    data object SettingNewPassword : AuthState
}

/** Why a sign-in step failed, mapped to a message on the login screen. */
enum class AuthError {
    NOT_CONFIGURED, INVALID_CREDENTIALS, EMAIL_NOT_CONFIRMED, USER_EXISTS, WEAK_PASSWORD, INVALID_EMAIL,
    LINK_EXPIRED, NETWORK, UNKNOWN,
}

sealed interface AuthResult {
    data object Success : AuthResult
    /** Sign-up worked but the email address must be confirmed before signing in. */
    data object ConfirmEmail : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

/**
 * Supabase Auth for the companion app: email + password, via Supabase Auth's REST API,
 * plus the user's research-analysis consent (`consents` table).
 *
 * The session is kept in encrypted preferences and refreshed when it expires. The app only holds the project URL and anon key.
 * Email links use PKCE: they only complete on the phone that asked for them (it holds the code
 * verifier), so a link from anyone else can't sign this phone in.
 */
class AuthRepository(context: Context) {
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    val isConfigured = baseUrl.isNotBlank() && anonKey.isNotBlank()

    private val prefs = openPrefs(context.applicationContext)

    /**
     * The encrypted session store. If it can't be opened (e.g. the file came from another device
     * without its Keystore key), it's deleted and recreated: the person signs in again instead of
     * the app crashing at every launch.
     */
    private fun openPrefs(context: Context): SharedPreferences {
        fun open() = EncryptedSharedPreferences.create(
            PREFS_FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try {
            open()
        } catch (e: Exception) {
            Log.w(TAG, "Session store unreadable, starting fresh: ${e.javaClass.simpleName}")
            context.deleteSharedPreferences(PREFS_FILE)
            open()
        }
    }

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        _state.value = when {
            prefs.getString(KEY_REFRESH, null) != null ->
                AuthState.SignedIn(prefs.getString(KEY_EMAIL, null), prefs.getString(KEY_METHOD, METHOD_EMAIL)!!)
            prefs.getBoolean(KEY_GUEST, false) -> AuthState.Guest
            else -> AuthState.SignedOut
        }
    }

    // --- Email ---

    suspend fun signInWithEmail(email: String, password: String): AuthResult {
        if (!isConfigured) return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        val (code, body) = post("/auth/v1/token?grant_type=password", JSONObject().put("email", email).put("password", password))
            ?: return AuthResult.Failure(AuthError.NETWORK)
        return if (code in 200..299) {
            saveSession(body, METHOD_EMAIL); AuthResult.Success
        } else AuthResult.Failure(mapError(body))
    }

    /** [analysisOptOut] is the sign-up screen's research choice; the server stores it in `consents`. */
    suspend fun signUpWithEmail(email: String, password: String, analysisOptOut: Boolean): AuthResult {
        if (!isConfigured) return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        val verifier = newCodeVerifier()
        val (code, body) = post(
            "/auth/v1/signup?redirect_to=${enc(REDIRECT)}",
            withChallenge(
                JSONObject().put("email", email).put("password", password)
                    .put("data", JSONObject().put("analysis_opt_out", analysisOptOut)),
                verifier,
            ),
        )
            ?: return AuthResult.Failure(AuthError.NETWORK)
        if (code !in 200..299) return AuthResult.Failure(mapError(body))
        // With "Confirm email" on (the default), sign-up returns the user but no session.
        return if (body.has("access_token")) {
            saveSession(body, METHOD_EMAIL); AuthResult.Success
        } else {
            setPendingLink(LINK_SIGNUP, verifier); AuthResult.ConfirmEmail
        }
    }

    suspend fun sendPasswordReset(email: String): AuthResult {
        if (!isConfigured) return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        val verifier = newCodeVerifier()
        val (code, body) = post("/auth/v1/recover?redirect_to=${enc(REDIRECT)}", withChallenge(JSONObject().put("email", email), verifier))
            ?: return AuthResult.Failure(AuthError.NETWORK)
        if (code !in 200..299) return AuthResult.Failure(mapError(body))
        setPendingLink(LINK_RECOVERY, verifier)
        return AuthResult.Success
    }

    /** Sets a new password for the signed-in user (after opening a reset link). */
    suspend fun updatePassword(newPassword: String): AuthResult {
        val token = accessToken() ?: return AuthResult.Failure(AuthError.LINK_EXPIRED)
        val (code, body) = request("PUT", "/auth/v1/user", JSONObject().put("password", newPassword), bearer = token)
            ?: return AuthResult.Failure(AuthError.NETWORK)
        if (code !in 200..299) return AuthResult.Failure(mapError(body))
        _state.value = AuthState.SignedIn(prefs.getString(KEY_EMAIL, null), prefs.getString(KEY_METHOD, METHOD_EMAIL)!!)
        return AuthResult.Success
    }

    /**
     * Handles daedalus://auth-callback?code=… from a confirmation or password-reset email that this
     * phone asked for (or ?error=… when the link expired). Returns null for any other link, which
     * is ignored; a signed-in account is never replaced by a link.
     */
    suspend fun handleCallback(uri: Uri): AuthResult? {
        val pending = pendingLink() ?: return null
        val (type, verifier) = pending
        if (_state.value is AuthState.SignedIn && type != LINK_RECOVERY) return null
        val params = Uri.parse("x://x?" + (uri.encodedQuery ?: uri.encodedFragment ?: ""))
        if (params.getQueryParameter("error") != null) {
            Log.w(TAG, "Email link error: ${params.getQueryParameter("error_description")}")
            return AuthResult.Failure(AuthError.LINK_EXPIRED)
        }
        val authCode = params.getQueryParameter("code") ?: return null
        val (code, body) = post("/auth/v1/token?grant_type=pkce", JSONObject().put("auth_code", authCode).put("code_verifier", verifier))
            ?: return AuthResult.Failure(AuthError.NETWORK)
        if (code !in 200..299) return AuthResult.Failure(AuthError.LINK_EXPIRED)
        setPendingLink(null, null)
        saveSession(body, METHOD_EMAIL)
        if (type == LINK_RECOVERY) _state.value = AuthState.SettingNewPassword
        return AuthResult.Success
    }

    // --- Email links this phone is waiting for (PKCE) ---

    /** (type, code verifier) of the email link asked for in the last day, if any. */
    private fun pendingLink(): Pair<String, String>? {
        val at = prefs.getLong(KEY_PENDING_AT, 0)
        if (System.currentTimeMillis() - at > 24 * 3600_000L) return null
        val type = prefs.getString(KEY_PENDING_TYPE, null) ?: return null
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null) ?: return null
        return type to verifier
    }

    private fun setPendingLink(type: String?, verifier: String?) {
        prefs.edit().apply {
            if (type == null) remove(KEY_PENDING_TYPE).remove(KEY_PENDING_VERIFIER).remove(KEY_PENDING_AT)
            else putString(KEY_PENDING_TYPE, type).putString(KEY_PENDING_VERIFIER, verifier).putLong(KEY_PENDING_AT, System.currentTimeMillis())
        }.apply()
    }

    private fun newCodeVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun withChallenge(body: JSONObject, verifier: String): JSONObject {
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return body.put("code_challenge", Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            .put("code_challenge_method", "s256")
    }

    // --- Consent ---

    /** Whether the signed-in user opted out of research analysis, or null if it can't be read. */
    suspend fun analysisOptOut(): Boolean? {
        val token = accessToken() ?: return null
        val (code, body) = request("GET", "/rest/v1/consents?select=analysis_opt_out", null, bearer = token, objectResult = true)
            ?: return null
        return if (code in 200..299 && body.has("analysis_opt_out")) body.getBoolean("analysis_opt_out") else null
    }

    suspend fun setAnalysisOptOut(optOut: Boolean): Boolean {
        val token = accessToken() ?: return false
        val (code, _) = post("/rest/v1/rpc/set_analysis_opt_out", JSONObject().put("opt_out", optOut), bearer = token)
            ?: return false
        return code in 200..299
    }

    // --- Personal profile (Supabase `profiles` table, own row only) ---

    sealed interface RemoteProfile {
        data class Found(val json: JSONObject) : RemoteProfile
        data object Missing : RemoteProfile
        /** Offline or the server couldn't be reached. */
        data object Unavailable : RemoteProfile
    }

    suspend fun fetchProfile(): RemoteProfile {
        val token = accessToken() ?: return RemoteProfile.Unavailable
        val (code, body) = request("GET", "/rest/v1/profiles?select=*", null, bearer = token, objectResult = true)
            ?: return RemoteProfile.Unavailable
        return when {
            code in 200..299 -> RemoteProfile.Found(body)
            code == 406 -> RemoteProfile.Missing // no row yet
            else -> RemoteProfile.Unavailable
        }
    }

    /** Creates or replaces the signed-in user's profile row. */
    suspend fun saveProfile(json: JSONObject): Boolean {
        val token = accessToken() ?: return false
        val (code, _) = request(
            "POST", "/rest/v1/profiles", json, bearer = token,
            headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        ) ?: return false
        return code in 200..299
    }

    // --- Watch session files (private `sessions` bucket, one folder per user) ---

    /**
     * Uploads one session CSV to sessions/<user id>/<file name> and records it in `session_files`.
     * Safe to repeat: the file is overwritten and the row is kept as is.
     */
    suspend fun uploadSessionFile(fileName: String, bytes: ByteArray, mode: String?, startedAt: String?): Boolean {
        val token = accessToken() ?: return false
        val userId = userIdFrom(token) ?: return false
        val objectPath = "$userId/$fileName"
        val (code, body) = request(
            "POST", "/storage/v1/object/sessions/$userId/${Uri.encode(fileName)}", null, bearer = token,
            headers = mapOf("Content-Type" to "text/csv", "x-upsert" to "true"), bytes = bytes,
        ) ?: return false
        if (code !in 200..299) {
            Log.w(TAG, "Session upload failed ($code): $body"); return false
        }
        val row = JSONObject().put("object_path", objectPath).put("mode", mode ?: JSONObject.NULL)
            .put("started_at", startedAt ?: JSONObject.NULL)
        val (rowCode, rowBody) = request(
            "POST", "/rest/v1/session_files?on_conflict=object_path", row, bearer = token,
            headers = mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal"),
        ) ?: return false
        if (rowCode !in 200..299) Log.w(TAG, "Session row failed ($rowCode): $rowBody")
        return rowCode in 200..299
    }

    /** The signed-in user's ID, or null when signed out / offline. */
    suspend fun userId(): String? = accessToken()?.let(::userIdFrom)

    /** The signed-in user's ID from the saved session, without a network call. */
    fun currentUserId(): String? = prefs.getString(KEY_ACCESS, null)?.let(::userIdFrom)

    /** The user's ID (the `sub` claim) from a Supabase access token. */
    private fun userIdFrom(token: String): String? = runCatching {
        val payload = token.split(".")[1]
        JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).getString("sub")
    }.getOrNull()

    // --- Session ---

    /** Refreshes the access token if it has expired. Keeps the user signed in when offline. */
    suspend fun refreshIfNeeded() {
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return
        if (System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES_AT, 0) - 60_000) return
        val (code, body) = post("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh)) ?: return
        when {
            code in 200..299 -> saveSession(body, prefs.getString(KEY_METHOD, METHOD_EMAIL)!!)
            code in 400..499 -> clearSession() // refresh token revoked or expired
        }
    }

    /** A valid access token for calls to Supabase, or null when signed out / offline. */
    suspend fun accessToken(): String? {
        refreshIfNeeded()
        return prefs.getString(KEY_ACCESS, null)
    }

    suspend fun signOut() {
        prefs.getString(KEY_ACCESS, null)?.let { token -> post("/auth/v1/logout", JSONObject(), bearer = token) }
        clearSession()
        prefs.edit().putBoolean(KEY_GUEST, false).apply()
        _state.value = AuthState.SignedOut
    }

    /** Uses the app on this phone only. Sign-in stays available from Profile. */
    fun continueAsGuest() {
        prefs.edit().putBoolean(KEY_GUEST, true).apply()
        _state.value = AuthState.Guest
    }

    private fun saveSession(body: JSONObject, method: String) {
        val email = body.optJSONObject("user")?.optString("email")?.ifBlank { null }
        prefs.edit()
            .putString(KEY_ACCESS, body.getString("access_token"))
            .putString(KEY_REFRESH, body.getString("refresh_token"))
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + body.optLong("expires_in", 3600) * 1000)
            .putString(KEY_EMAIL, email ?: prefs.getString(KEY_EMAIL, null))
            .putString(KEY_METHOD, method)
            .putBoolean(KEY_GUEST, false)
            .apply()
        _state.value = AuthState.SignedIn(prefs.getString(KEY_EMAIL, null), method)
    }

    private fun clearSession() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES_AT).remove(KEY_EMAIL).remove(KEY_METHOD).apply()
        if (_state.value is AuthState.SignedIn || _state.value is AuthState.SettingNewPassword) _state.value = AuthState.SignedOut
    }

    private fun mapError(body: JSONObject): AuthError {
        val code = body.optString("error_code").ifBlank { body.optString("code") }.ifBlank { body.optString("error") }
        val text = (body.optString("msg") + " " + body.optString("error_description") + " " + body.optString("message")).lowercase()
        return when {
            code == "invalid_credentials" || "invalid login credentials" in text -> AuthError.INVALID_CREDENTIALS
            code == "email_not_confirmed" || "not confirmed" in text -> AuthError.EMAIL_NOT_CONFIRMED
            code == "user_already_exists" || "already registered" in text -> AuthError.USER_EXISTS
            code == "weak_password" || "password should be" in text -> AuthError.WEAK_PASSWORD
            code == "validation_failed" || "invalid format" in text || "email address" in text -> AuthError.INVALID_EMAIL
            else -> {
                Log.w(TAG, "Auth error: $body"); AuthError.UNKNOWN
            }
        }
    }

    private suspend fun post(path: String, json: JSONObject, bearer: String? = null) = request("POST", path, json, bearer)

    /** Calls Supabase; returns (HTTP status, JSON object body), or null when offline. */
    internal suspend fun request(
        method: String,
        path: String,
        json: JSONObject?,
        bearer: String? = null,
        objectResult: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        bytes: ByteArray? = null,
    ): Pair<Int, JSONObject>? = requestText(method, path, json?.toString()?.toByteArray() ?: bytes, bearer, objectResult, headers)
        ?.let { (code, text) -> code to (runCatching { JSONObject(text) }.getOrDefault(JSONObject())) }

    /** Like [request], but returns the raw body text (for list responses). */
    internal suspend fun requestText(
        method: String,
        path: String,
        /** Request body: JSON (default Content-Type) or raw bytes with a Content-Type in [headers]. */
        body: ByteArray?,
        bearer: String? = null,
        objectResult: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = if ((body?.size ?: 0) > 100_000) 60_000 else 15_000
                    setRequestProperty("apikey", anonKey)
                    // Storage rejects a JSON content type on requests without a body (e.g. DELETE).
                    if (body != null) setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${bearer ?: anonKey}")
                    // Database API: return a single row as an object rather than a list.
                    if (objectResult) setRequestProperty("Accept", "application/vnd.pgrst.object+json")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to text
            }.onFailure { Log.w(TAG, "Auth request failed: ${it.message}") }.getOrNull()
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val REDIRECT = "daedalus://auth-callback"
        const val METHOD_EMAIL = "email"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_EMAIL = "email"
        private const val KEY_METHOD = "method"
        private const val KEY_GUEST = "guest"
        private const val KEY_PENDING_TYPE = "pending_link"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"
        private const val KEY_PENDING_AT = "pending_at"
        private const val LINK_SIGNUP = "signup"
        private const val LINK_RECOVERY = "recovery"
        private const val PREFS_FILE = "daedalus_auth"
    }
}
