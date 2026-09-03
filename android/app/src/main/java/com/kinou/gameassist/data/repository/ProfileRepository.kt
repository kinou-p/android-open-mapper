package com.kinou.gameassist.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.kinou.gameassist.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ProfileRepository private constructor(context: Context) {
    private val context: Context = context.applicationContext
    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val profilesDir: File = File(this.context.filesDir, "profiles")
    private val fileLock = Any()
    private val profileCache = mutableMapOf<String, GameProfile>()
    @Volatile
    private var isCacheLoaded = false

    private val _profilesFlow = MutableStateFlow<List<GameProfile>>(emptyList())
    val profilesFlow: StateFlow<List<GameProfile>> = _profilesFlow.asStateFlow()

    private fun updateFlowLocked() {
        _profilesFlow.value = profileCache.values.map { it.deepCopy() }
    }

    companion object {
        @Volatile
        private var instance: ProfileRepository? = null

        /**
         * Retourne l'instance Singleton de ProfileRepository.
         * Utiliser cette méthode depuis MainActivity, OverlayService et CommunityScreen
         * pour garantir un cache partagé unique entre tous les composants de l'application.
         */
        fun getInstance(context: Context): ProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: ProfileRepository(context.applicationContext).also { instance = it }
            }
        }

        private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

        fun validateAndSanitizeProfile(p: GameProfile): Boolean {
            if (p.id.isBlank() || !SAFE_ID_REGEX.matches(p.id)) {
                p.id = "profile_${UUID.randomUUID().toString().take(8)}"
            }
            if (p.name.isBlank()) p.name = "Custom Profile"
            if (p.packageName.isBlank()) p.packageName = "com.game.app"

            // Sanitize Joystick
            val joy = p.joystick
            if (!joy.centerX.isFinite()) joy.centerX = 0.18f else joy.centerX = joy.centerX.coerceIn(-0.5f, 1.5f)
            if (!joy.centerY.isFinite()) joy.centerY = 0.72f else joy.centerY = joy.centerY.coerceIn(-0.5f, 1.5f)
            if (!joy.radius.isFinite() || joy.radius <= 0f) joy.radius = 0.13f else joy.radius = joy.radius.coerceIn(0.01f, 1.0f)
            if (!joy.deadzone.isFinite() || joy.deadzone < 0f) joy.deadzone = 0.10f else joy.deadzone = joy.deadzone.coerceIn(0.0f, 0.5f)
            if (!joy.outerDeadzone.isFinite() || joy.outerDeadzone <= 0f) joy.outerDeadzone = 0.95f else joy.outerDeadzone = joy.outerDeadzone.coerceIn(0.5f, 1.0f)
            if (!joy.sprintThreshold.isFinite()) joy.sprintThreshold = 0.82f else joy.sprintThreshold = joy.sprintThreshold.coerceIn(0.2f, 1.0f)

            // Sanitize Camera
            val cam = p.camera
            if (!cam.rectX1.isFinite()) cam.rectX1 = 0.48f else cam.rectX1 = cam.rectX1.coerceIn(-0.5f, 1.5f)
            if (!cam.rectY1.isFinite()) cam.rectY1 = 0.12f else cam.rectY1 = cam.rectY1.coerceIn(-0.5f, 1.5f)
            if (!cam.rectX2.isFinite()) cam.rectX2 = 0.98f else cam.rectX2 = cam.rectX2.coerceIn(-0.5f, 1.5f)
            if (!cam.rectY2.isFinite()) cam.rectY2 = 0.92f else cam.rectY2 = cam.rectY2.coerceIn(-0.5f, 1.5f)
            if (!cam.sensitivityX.isFinite() || cam.sensitivityX <= 0f) cam.sensitivityX = 1.45f else cam.sensitivityX = cam.sensitivityX.coerceIn(0.05f, 10.0f)
            if (!cam.sensitivityY.isFinite() || cam.sensitivityY <= 0f) cam.sensitivityY = 1.15f else cam.sensitivityY = cam.sensitivityY.coerceIn(0.05f, 10.0f)
            if (!cam.deadzone.isFinite() || cam.deadzone < 0f) cam.deadzone = 0.08f else cam.deadzone = cam.deadzone.coerceIn(0.0f, 0.5f)
            if (!cam.smoothing.isFinite()) cam.smoothing = 0.22f else cam.smoothing = cam.smoothing.coerceIn(0.0f, 1.0f)
            if (!cam.acceleration.isFinite()) cam.acceleration = 1.25f else cam.acceleration = cam.acceleration.coerceIn(0.5f, 5.0f)
            if (!cam.flickBoost.isFinite()) cam.flickBoost = 2.0f else cam.flickBoost = cam.flickBoost.coerceIn(1.0f, 5.0f)
            if (!cam.flickThreshold.isFinite()) cam.flickThreshold = 0.80f else cam.flickThreshold = cam.flickThreshold.coerceIn(0.5f, 1.0f)
            if (!cam.adsSensitivityMultiplier.isFinite()) cam.adsSensitivityMultiplier = 0.70f else cam.adsSensitivityMultiplier = cam.adsSensitivityMultiplier.coerceIn(0.1f, 3.0f)
            if (!cam.maxStepPixels.isFinite() || cam.maxStepPixels <= 0f) cam.maxStepPixels = 55.0f else cam.maxStepPixels = cam.maxStepPixels.coerceIn(1.0f, 150.0f)

            // Sanitize Buttons
            if (p.buttons.size > 50) {
                p.buttons = p.buttons.take(50).toMutableList()
            }
            for (btn in p.buttons) {
                if (!btn.x.isFinite()) btn.x = 0.5f else btn.x = btn.x.coerceIn(-0.5f, 1.5f)
                if (!btn.y.isFinite()) btn.y = 0.5f else btn.y = btn.y.coerceIn(-0.5f, 1.5f)
                if (!btn.radius.isFinite() || btn.radius <= 0f) btn.radius = 0.045f else btn.radius = btn.radius.coerceIn(0.01f, 0.5f)
                if (btn.label.length > 100) btn.label = btn.label.take(100)
                if (btn.gamepadButton.length > 50) btn.gamepadButton = btn.gamepadButton.take(50)
            }
            return true
        }
    }

    init {
        // Pre-populate in-memory cache with default profiles immediately (0ms, no disk I/O)
        // so synchronous access during startup is instant and non-blocking.
        synchronized(fileLock) {
            val codmMp = createDefaultCodmProfile()
            val codmBr = createDefaultCodmBrProfile()
            profileCache[codmMp.id] = codmMp
            profileCache[codmBr.id] = codmBr
            updateFlowLocked()
        }
        // Load profiles from disk asynchronously on Dispatchers.IO to avoid StrictMode violations
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(fileLock) {
                if (!isCacheLoaded) {
                    ensureDiskLoadedLocked()
                }
            }
        }
    }

    private fun ensureDiskLoadedLocked() {
        if (!profilesDir.exists()) {
            profilesDir.mkdirs()
            initializeDefaultProfiles()
        } else {
            loadCacheLocked()
        }
    }

    private fun initializeDefaultProfiles() {
        val codmMp = createDefaultCodmProfile()
        saveProfileInternal(codmMp)
        val codmBr = createDefaultCodmBrProfile()
        saveProfileInternal(codmBr)
    }

    private fun loadCacheLocked() {
        val files = profilesDir.listFiles { file -> file.extension == "json" }
        profileCache.clear()
        if (files != null) {
            for (f in files) {
                try {
                    val jsonText = f.readText()
                    val p = json.decodeFromString<GameProfile>(jsonText)
                    ensureRolesMigrated(p)
                    validateAndSanitizeProfile(p)
                    profileCache[p.id] = p
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (profileCache.isEmpty()) {
            val def = createDefaultCodmProfile()
            saveProfileInternal(def)
        } else {
            isCacheLoaded = true
            updateFlowLocked()
        }
    }

    suspend fun getAllProfilesAsync(): List<GameProfile> = withContext(Dispatchers.IO) {
        getAllProfiles()
    }

    fun getAllProfiles(): List<GameProfile> {
        synchronized(fileLock) {
            if (!isCacheLoaded) {
                ensureDiskLoadedLocked()
            }
            return _profilesFlow.value
        }
    }

    private fun getProfileFile(id: String): File? {
        if (!SAFE_ID_REGEX.matches(id)) return null
        val target = File(profilesDir, "$id.json")
        return try {
            val allowedDir = profilesDir.canonicalPath + File.separator
            if (target.canonicalPath.startsWith(allowedDir)) target else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getProfileAsync(id: String): GameProfile? = withContext(Dispatchers.IO) {
        getProfile(id)
    }

    fun getProfile(id: String): GameProfile? {
        if (!SAFE_ID_REGEX.matches(id)) return null
        synchronized(fileLock) {
            if (!isCacheLoaded) {
                ensureDiskLoadedLocked()
            }
            profileCache[id]?.let { return it.deepCopy() }

            val file = getProfileFile(id) ?: return null
            if (!file.exists()) return null
            return try {
                val p = json.decodeFromString<GameProfile>(file.readText())
                ensureRolesMigrated(p)
                validateAndSanitizeProfile(p)
                profileCache[p.id] = p
                p.deepCopy()
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun saveProfileAsync(profile: GameProfile) = withContext(Dispatchers.IO) {
        saveProfile(profile)
    }

    suspend fun deleteProfileAsync(id: String): Boolean = withContext(Dispatchers.IO) {
        deleteProfile(id)
    }

    suspend fun duplicateProfileAsync(profile: GameProfile): GameProfile = withContext(Dispatchers.IO) {
        duplicateProfile(profile)
    }

    suspend fun importProfileFromJsonAsync(json: String): GameProfile? = withContext(Dispatchers.IO) {
        importProfileFromJson(json)
    }

    fun saveProfile(profile: GameProfile) {
        synchronized(fileLock) {
            saveProfileInternal(profile)
        }
    }

    private fun saveProfileInternal(profile: GameProfile) {
        val sanitized = profile.deepCopy()
        validateAndSanitizeProfile(sanitized)
        val file = getProfileFile(sanitized.id) ?: return
        val atomicFile = AtomicFile(file)
        val jsonBytes = json.encodeToString(sanitized).toByteArray(Charsets.UTF_8)
        var fos: FileOutputStream? = null
        try {
            fos = atomicFile.startWrite()
            fos.write(jsonBytes)
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Exception) {}
            atomicFile.finishWrite(fos)
            profileCache[sanitized.id] = sanitized.deepCopy()
            isCacheLoaded = true
            updateFlowLocked()
        } catch (e: Exception) {
            if (fos != null) {
                atomicFile.failWrite(fos)
            }
            e.printStackTrace()
        }
    }

    fun deleteProfile(id: String): Boolean {
        if (!SAFE_ID_REGEX.matches(id)) return false
        synchronized(fileLock) {
            val existing = getProfile(id)
            if (existing?.customScreenshotPath != null) {
                ScreenshotManager.deleteScreenshot(context, existing.customScreenshotPath)
            }
            profileCache.remove(id)
            val file = getProfileFile(id)
            val deleted = file?.delete() ?: false
            updateFlowLocked()
            return deleted
        }
    }

    fun exportProfileToJson(profile: GameProfile): String {
        return json.encodeToString(profile)
    }

    fun importProfileFromJson(jsonText: String): GameProfile? {
        return try {
            val imported = json.decodeFromString<GameProfile>(jsonText)
            ensureRolesMigrated(imported)
            imported.customScreenshotPath = sanitizeImportedScreenshotPath(imported.customScreenshotPath)
            if (!validateAndSanitizeProfile(imported)) {
                return null
            }
            // Ensure unique ID if imported
            if (getProfile(imported.id) != null) {
                imported.id = "profile_${UUID.randomUUID().toString().take(8)}"
                imported.name = "${imported.name} (Importé)"
            }
            saveProfile(imported)
            imported
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    /**
     * Neutralise tout chemin de capture d'écran non fiable (profil importé depuis le Hub
     * communautaire ou un JSON externe). Un chemin arbitraire permettrait la lecture
     * (loadScreenshotBitmap) et la suppression (deleteScreenshot) de fichiers locaux.
     */
    private fun sanitizeImportedScreenshotPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (ScreenshotManager.isPathInScreenshotsDir(context, path)) path else null
    }

    private fun ensureRolesMigrated(p: GameProfile) {
        for (btn in p.buttons) {
            @Suppress("SENSELESS_COMPARISON")
            if ((btn.role as ButtonRole?) == null) {
                btn.role = when {
                    btn.id.contains("fire", ignoreCase = true) || btn.label.contains("tir", ignoreCase = true) || btn.label.contains("fire", ignoreCase = true) || btn.gamepadButton.equals("BUTTON_R2", ignoreCase = true) || btn.gamepadButton.equals("TRIGGER_R2", ignoreCase = true) -> ButtonRole.FIRE
                    btn.id.contains("reload", ignoreCase = true) || btn.label.contains("recharg", ignoreCase = true) || btn.label.contains("reload", ignoreCase = true) -> ButtonRole.RELOAD
                    btn.id.contains("ads", ignoreCase = true) || btn.label.contains("visée", ignoreCase = true) || btn.label.contains("visee", ignoreCase = true) || btn.gamepadButton.equals("BUTTON_L2", ignoreCase = true) || btn.gamepadButton.equals("TRIGGER_L2", ignoreCase = true) -> ButtonRole.ADS
                    else -> ButtonRole.NORMAL
                }
            }
            @Suppress("SENSELESS_COMPARISON")
            if ((btn.mode as ButtonMode?) == null) {
                btn.mode = ButtonMode.HOLD
            }
        }
    }

    fun duplicateProfile(profile: GameProfile): GameProfile {
        val newId = "profile_${UUID.randomUUID().toString().take(8)}"
        val newScreenshotPath = ScreenshotManager.duplicateScreenshot(context, newId, profile.customScreenshotPath)
        val copy = profile.deepCopy().copy(
            id = newId,
            name = "${profile.name} (Copie)",
            customScreenshotPath = newScreenshotPath,
            buttons = profile.buttons.map { it.copy(id = "btn_${UUID.randomUUID().toString().take(8)}") }.toMutableList()
        )
        saveProfile(copy)
        return copy
    }

    fun createDefaultCodmProfile(): GameProfile {
        val lang = com.kinou.gameassist.data.language.LanguageManager.getCurrentLanguage(context)
        val name = when (lang) {
            "fr" -> "CoD Mobile - Multijoueur"
            "es" -> "CoD Mobile - Multijugador"
            "pt" -> "CoD Mobile - Multijogador"
            else -> "CoD Mobile - Multiplayer"
        }
        val desc = when (lang) {
            "fr" -> "Configuration officielle Multijoueur avec visée réactive et sprint lock."
            "es" -> "Configuración oficial Multijugador con apuntado reactivo y bloqueo de sprint."
            "pt" -> "Configuração oficial Multijogador com mira responsiva e sprint lock."
            else -> "Official Multiplayer configuration with responsive aim and sprint lock."
        }
        val btnFire = when (lang) { "fr" -> "Tir Principal"; "es" -> "Disparo Principal"; "pt" -> "Tiro Principal"; else -> "Primary Fire" }
        val btnAds = when (lang) { "fr" -> "Visée Précise (ADS)"; "es" -> "Apuntado con Mira (ADS)"; "pt" -> "Mirar (ADS)"; else -> "ADS Scope Aim" }
        val btnJump = when (lang) { "fr" -> "Sauter / Franchir"; "es" -> "Saltar / Trepar"; "pt" -> "Pular / Escalar"; else -> "Jump / Vault" }
        val btnSlide = when (lang) { "fr" -> "Glissade / S'accroupir"; "es" -> "Deslizarse / Agacharse"; "pt" -> "Deslizar / Agachar"; else -> "Slide / Crouch" }
        val btnReload = when (lang) { "fr" -> "Recharger"; "es" -> "Recargar"; "pt" -> "Recarregar"; else -> "Reload" }
        val btnSwitch = when (lang) { "fr" -> "Changer d'arme"; "es" -> "Cambiar de Arma"; "pt" -> "Trocar de Arma"; else -> "Switch Weapon" }
        val btnFrag = when (lang) { "fr" -> "Grenade Frag"; "es" -> "Granada Letal"; "pt" -> "Granada Letal"; else -> "Lethal Grenade" }
        val btnFlash = when (lang) { "fr" -> "Flash / Fumi"; "es" -> "Granada Táctica"; "pt" -> "Granada Tática"; else -> "Tactical / Flash" }
        val btnOperator = when (lang) { "fr" -> "Opérateur"; "es" -> "Habilidad de Operador"; "pt" -> "Habilidade de Operador"; else -> "Operator Skill" }
        val btnProne = when (lang) { "fr" -> "Couché (Prone)"; "es" -> "Cuerpo a Tierra"; "pt" -> "Deitar (Prone)"; else -> "Prone" }
        val btnSprint = when (lang) { "fr" -> "Sprint Forcé (L3)"; "es" -> "Sprint Forzado (L3)"; "pt" -> "Sprint Forçado (L3)"; else -> "Sprint Click (L3)" }
        val btnMelee = when (lang) { "fr" -> "Attaque Couteau (R3)"; "es" -> "Ataque Cuerpo a Cuerpo (R3)"; "pt" -> "Ataque Corpo a Corpo (R3)"; else -> "Melee Attack (R3)" }
        val btnMap = when (lang) { "fr" -> "Carte"; "es" -> "Mapa"; "pt" -> "Mapa"; else -> "Map" }

        return GameProfile(
            id = "codm_multiplayer_default",
            name = name,
            packageName = "com.activision.callofduty.shooter",
            description = desc,
            joystick = JoystickConfig(
                enabled = true,
                centerX = 0.18f,
                centerY = 0.72f,
                radius = 0.13f,
                deadzone = 0.10f,
                sprintThreshold = 0.82f
            ),
            camera = CameraConfig(
                enabled = true,
                rectX1 = 0.48f,
                rectY1 = 0.12f,
                rectX2 = 0.98f,
                rectY2 = 0.92f,
                sensitivityX = 1.45f,
                sensitivityY = 1.15f,
                deadzone = 0.08f,
                smoothing = 0.22f,
                acceleration = 1.25f
            ),
            buttons = mutableListOf(
                ButtonConfig("btn_fire", btnFire, "BUTTON_R2", 0.85f, 0.70f, 0.055f, ButtonMode.HOLD, ButtonRole.FIRE),
                ButtonConfig("btn_ads", btnAds, "BUTTON_L2", 0.85f, 0.40f, 0.050f, ButtonMode.HOLD, ButtonRole.ADS),
                ButtonConfig("btn_jump", btnJump, "BUTTON_A", 0.94f, 0.72f, 0.045f, ButtonMode.HOLD, ButtonRole.NORMAL),
                ButtonConfig("btn_slide", btnSlide, "BUTTON_B", 0.90f, 0.86f, 0.045f, ButtonMode.HOLD, ButtonRole.NORMAL),
                ButtonConfig("btn_reload", btnReload, "BUTTON_X", 0.76f, 0.85f, 0.045f, ButtonMode.TAP, ButtonRole.RELOAD),
                ButtonConfig("btn_switch", btnSwitch, "BUTTON_Y", 0.72f, 0.94f, 0.045f, ButtonMode.TAP, ButtonRole.NORMAL),
                ButtonConfig("btn_grenade_lethal", btnFrag, "BUTTON_R1", 0.76f, 0.58f, 0.045f, ButtonMode.HOLD, ButtonRole.NORMAL),
                ButtonConfig("btn_grenade_tactical", btnFlash, "BUTTON_L1", 0.68f, 0.58f, 0.045f, ButtonMode.HOLD, ButtonRole.NORMAL),
                ButtonConfig("btn_scorestreak", "Scorestreak", "DPAD_UP", 0.68f, 0.74f, 0.040f, ButtonMode.TAP, ButtonRole.NORMAL),
                ButtonConfig("btn_operator", btnOperator, "DPAD_RIGHT", 0.75f, 0.42f, 0.045f, ButtonMode.TAP, ButtonRole.NORMAL),
                ButtonConfig("btn_prone", btnProne, "DPAD_DOWN", 0.96f, 0.88f, 0.040f, ButtonMode.HOLD, ButtonRole.NORMAL),
                ButtonConfig("btn_sprint_click", btnSprint, "BUTTON_THUMBL", 0.18f, 0.56f, 0.040f, ButtonMode.TAP, ButtonRole.NORMAL),
                ButtonConfig("btn_melee", btnMelee, "BUTTON_THUMBR", 0.80f, 0.78f, 0.040f, ButtonMode.TAP, ButtonRole.NORMAL),
                ButtonConfig("btn_map", btnMap, "BUTTON_SELECT", 0.95f, 0.08f, 0.040f, ButtonMode.TAP, ButtonRole.NORMAL)
            )
        )
    }

    fun createDefaultCodmBrProfile(): GameProfile {
        val lang = com.kinou.gameassist.data.language.LanguageManager.getCurrentLanguage(context)
        val p = createDefaultCodmProfile()
        p.id = "codm_br_default"
        p.name = "CoD Mobile - Battle Royale"
        p.description = when (lang) {
            "fr" -> "Profil Battle Royale avec soins rapides et plaques d'armure sur le D-Pad."
            "es" -> "Perfil Battle Royale con curaciones rápidas y placas de blindaje en la cruceta."
            "pt" -> "Perfil Battle Royale com cura rápida e placas de blindagem no direcional."
            else -> "Battle Royale profile with quick heal and armor plates on D-Pad."
        }
        val btnArmor = when (lang) {
            "fr" -> "Plaque / Soin BR"
            "es" -> "Blindaje / Curación BR"
            "pt" -> "Placa / Cura BR"
            else -> "Armor Plate / Heal"
        }
        p.buttons.add(
            ButtonConfig("btn_armor", btnArmor, "DPAD_LEFT", 0.62f, 0.88f, 0.045f, ButtonMode.TAP, ButtonRole.NORMAL)
        )
        return p
    }
}
