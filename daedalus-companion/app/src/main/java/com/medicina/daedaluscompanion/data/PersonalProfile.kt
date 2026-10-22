package com.medicina.daedaluscompanion.data

import org.json.JSONArray
import org.json.JSONObject

enum class Sex { FEMALE, MALE, INTERSEX, UNDISCLOSED }

enum class Lifestyle { SEDENTARY, LIGHT, ACTIVE, VERY_ACTIVE }

/** Parkinson's symptoms the person reports during setup (motor first, then non-motor). */
enum class Symptom {
    TREMOR, SLOWNESS, STIFFNESS, FREEZING, BALANCE, DYSKINESIA, WEARING_OFF, HANDWRITING, VOICE, SWALLOWING,
    SLEEP, FATIGUE, MOOD, MEMORY, CONSTIPATION, SMELL, DIZZINESS, PAIN,
}

/**
 * Personal information from the first-sign-in setup. [birthDate] is ISO yyyy-MM-dd.
 * Medications are not here: they go into the medication schedule (LocalStore.schedule()).
 */
data class PersonalProfile(
    val firstName: String,
    val lastName: String = "",
    val birthDate: String? = null,
    val sex: Sex? = null,
    val lifestyle: Lifestyle? = null,
    val symptoms: Set<Symptom> = emptySet(),
) {
    /** Row for the Supabase `profiles` table. */
    fun toJson(): JSONObject = JSONObject()
        .put("first_name", firstName)
        .put("last_name", lastName)
        .put("birth_date", birthDate ?: JSONObject.NULL)
        .put("sex", sex?.name?.lowercase() ?: JSONObject.NULL)
        .put("lifestyle", lifestyle?.name?.lowercase() ?: JSONObject.NULL)
        .put("symptoms", JSONArray(symptoms.map { it.name.lowercase() }))

    companion object {
        fun fromJson(o: JSONObject): PersonalProfile {
            val symptoms = o.optJSONArray("symptoms")
            return PersonalProfile(
                firstName = o.optString("first_name"),
                lastName = o.optString("last_name"),
                birthDate = o.optString("birth_date").takeUnless { o.isNull("birth_date") || it.isBlank() },
                sex = enumOrNull<Sex>(o, "sex"),
                lifestyle = enumOrNull<Lifestyle>(o, "lifestyle"),
                symptoms = (0 until (symptoms?.length() ?: 0))
                    .mapNotNull { i -> runCatching { Symptom.valueOf(symptoms!!.getString(i).uppercase()) }.getOrNull() }
                    .toSet(),
            )
        }

        private inline fun <reified E : Enum<E>> enumOrNull(o: JSONObject, key: String): E? =
            if (o.isNull(key)) null else runCatching { enumValueOf<E>(o.getString(key).uppercase()) }.getOrNull()
    }
}
