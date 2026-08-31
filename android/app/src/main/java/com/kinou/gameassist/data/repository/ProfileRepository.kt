package com.kinou.gameassist.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kinou.gameassist.data.model.*
import java.io.File
import java.util.UUID

class ProfileRepository(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val profilesDir: File = File(context.filesDir, "profiles")

    init {
        if (!profilesDir.exists()) {
            profilesDir.mkdirs()
            initializeDefaultProfiles()
        }
    }

    private fun initializeDefaultProfiles() {
        val codmMp = createDefaultCodmProfile()
        saveProfile(codmMp)

        val codmBr = createDefaultCodmBrProfile()
        saveProfile(codmBr)
    }

    fun getAllProfiles(): List<GameProfile> {
        val list = mutableListOf<GameProfile>()
        val files = profilesDir.listFiles { file -> file.extension == "json" } ?: return list

        for (f in files) {
            try {
                val json = f.readText()
                val p = gson.fromJson(json, GameProfile::class.java)
                if (p != null) {
                    ensureRolesMigrated(p)
                    list.add(p)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (list.isEmpty()) {
            val def = createDefaultCodmProfile()
            saveProfile(def)
            list.add(def)
        }
        return list
    }

    fun getProfile(id: String): GameProfile? {
        val file = File(profilesDir, "$id.json")
        if (!file.exists()) return null
        return try {
            val p = gson.fromJson(file.readText(), GameProfile::class.java)
            p?.let { ensureRolesMigrated(it) }
            p
        } catch (e: Exception) {
            null
        }
    }

    fun saveProfile(profile: GameProfile) {
        val file = File(profilesDir, "${profile.id}.json")
        file.writeText(gson.toJson(profile))
    }

    fun deleteProfile(id: String): Boolean {
        val existing = getProfile(id)
        if (existing?.customScreenshotPath != null) {
            ScreenshotManager.deleteScreenshot(existing.customScreenshotPath)
        }
        val file = File(profilesDir, "$id.json")
        return file.delete()
    }

    fun exportProfileToJson(profile: GameProfile): String {
        return gson.toJson(profile)
    }

    fun importProfileFromJson(json: String): GameProfile? {
        return try {
            val imported = gson.fromJson(json, GameProfile::class.java)
            if (imported != null) {
                ensureRolesMigrated(imported)
                // Ensure unique ID if imported
                if (getProfile(imported.id) != null) {
                    imported.id = "profile_${UUID.randomUUID().toString().take(8)}"
                    imported.name = "${imported.name} (Importé)"
                }
                saveProfile(imported)
                imported
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
        }
    }

    fun duplicateProfile(profile: GameProfile): GameProfile {
        val newId = "profile_${UUID.randomUUID().toString().take(8)}"
        val newScreenshotPath = ScreenshotManager.duplicateScreenshot(context, newId, profile.customScreenshotPath)
        val copy = profile.copy(
            id = newId,
            name = "${profile.name} (Copie)",
            customScreenshotPath = newScreenshotPath,
            buttons = profile.buttons.map { it.copy(id = "btn_${UUID.randomUUID().toString().take(8)}") }.toMutableList()
        )
        val isFr = com.kinou.gameassist.data.language.LanguageManager.isFrench(context)
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
