package com.example.util

import com.example.data.model.Belt
import com.example.data.model.JudoGroup
import org.json.JSONObject
import java.util.Locale

data class ExtractedMemberInfo(
    val firstName: String = "",
    val lastName: String = "",
    val birthDate: String = "", // Format AAAA-MM-JJ
    val gender: String = "",    // "M", "F", or ""
    val phone: String = "",
    val notes: String = "",
    val qrCode: String = "",
    val cardNumber: String = "",
    val season: String = "",
    val photoUrl: String = "",
    val group: JudoGroup? = null,
    val belt: Belt = Belt.BLANCHE,
    val rawScannedData: String = "",
    val portraitDetected: Boolean = false,
    val ocrRecognized: Boolean = false
)

object CardDataParser {

    private val BLACKLIST_NAME_WORDS = setOf(
        "JUDO", "CLUB", "SEDDOUK", "FEDERATION", "FRANCAISE", "FRANÇAISE", "ALGERIENNE", "ALGÉRIENNE",
        "REPUBLIQUE", "RÉPUBLIQUE", "FFJDA", "CARTE", "ADHERENT", "ADHÉRENT", "MEMBRE", "COTISATION",
        "SAISON", "PASSEPORT", "SPORTIF", "LICENCE", "IDENTITE", "IDENTITÉ", "NATIONALE",
        "MINISTERE", "MINISTÈRE", "JEUNESSE", "SPORTS", "ASSOCIATION", "DOJO", "TATAMI",
        "PRESENCE", "PRÉSENCE", "ANNEE", "ANNÉE", "VALABLE", "DATE", "NOM", "PRENOM", "PRÉNOM",
        "SEXE", "TEL", "TÉL", "TELEPHONE", "TÉLÉPHONE", "NAISSANCE", "SIGNATURE", "DIRECTION",
        "LIGUE", "COMITE", "COMITÉ", "REGION", "RÉGION", "VILLE", "WILAYA", "COMMUNE", "CODE",
        "PHOTO", "PROFIL", "ATTESTATION", "CERTIFICAT", "MEDICAL", "MÉDICAL", "SURNAME", "NAME",
        "FIRSTNAME", "LASTNAME", "GENDER", "BIRTH", "CARD", "EXPIRES", "DELIVRE", "DÉLIVRÉ",
        "TUTEUR", "PARENT", "SECTION", "CATEGORIE", "CATÉGORIE", "GRADE", "CEINTURE", "RESPONSABLE"
    )

    fun parseCardData(raw: String): ExtractedMemberInfo {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ExtractedMemberInfo()

        // 1. Try parsing JSON format
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val firstName = json.optString("firstName", json.optString("prenom", "")).trim()
                val lastName = json.optString("lastName", json.optString("nom", "")).trim()
                val birthDateRaw = json.optString("birthDate", json.optString("dateNaissance", "")).trim()
                val birthDate = normalizeDateToIso(birthDateRaw)
                val gender = normalizeGender(json.optString("gender", json.optString("sexe", "")))
                val phone = cleanPhoneNumber(json.optString("phone", json.optString("telephone", "")))
                val cardNumber = json.optString("cardNumber", json.optString("licence", json.optString("numero", ""))).trim()
                val qrCode = json.optString("qrCode", if (cardNumber.isNotBlank()) "JCS-$cardNumber" else "")
                val beltStr = json.optString("belt", json.optString("ceinture", "BLANCHE"))
                val groupStr = json.optString("group", json.optString("categorie", ""))
                val season = json.optString("season", json.optString("saison", ""))
                val photoUrl = json.optString("photoUrl", json.optString("photo", ""))
                val notes = json.optString("notes", "")

                val group = if (groupStr.isNotBlank()) JudoGroup.fromString(groupStr)
                else if (birthDate.isNotBlank()) JudoGroup.fromBirthDate(birthDate)
                else null

                return ExtractedMemberInfo(
                    firstName = formatFirstName(firstName),
                    lastName = formatLastName(lastName),
                    birthDate = birthDate,
                    gender = gender,
                    phone = phone,
                    notes = notes,
                    qrCode = qrCode,
                    cardNumber = cardNumber,
                    season = season,
                    photoUrl = photoUrl,
                    group = group,
                    belt = Belt.fromString(beltStr),
                    rawScannedData = trimmed,
                    ocrRecognized = (firstName.isNotBlank() || lastName.isNotBlank() || birthDate.isNotBlank())
                )
            } catch (_: Exception) {}
        }

        // 2. Try parsing Delimited Key-Value strings (e.g., KEY:VALUE;KEY:VALUE or KEY=VALUE)
        if (trimmed.contains(";") || trimmed.contains("|")) {
            val delimiter = if (trimmed.contains(";")) ";" else "|"
            val parts = trimmed.split(delimiter)
            val map = mutableMapOf<String, String>()
            for (part in parts) {
                val kv = part.split(":", "=")
                if (kv.size == 2) {
                    map[kv[0].trim().uppercase(Locale.ROOT)] = kv[1].trim()
                }
            }
            if (map.isNotEmpty()) {
                val firstName = map["FIRSTNAME"] ?: map["PRENOM"] ?: map["PRÉNOM"] ?: ""
                val lastName = map["LASTNAME"] ?: map["NOM"] ?: ""
                val birthDate = normalizeDateToIso(map["BIRTHDATE"] ?: map["DOB"] ?: map["NAISSANCE"] ?: "")
                val gender = normalizeGender(map["GENDER"] ?: map["SEXE"] ?: "")
                val phone = cleanPhoneNumber(map["PHONE"] ?: map["TEL"] ?: map["TÉL"] ?: "")
                val cardNumber = map["CARD"] ?: map["LICENCE"] ?: map["ID"] ?: map["NUMERO"] ?: ""
                val qr = map["QR"] ?: if (cardNumber.isNotBlank()) "JCS-$cardNumber" else ""
                val beltStr = map["BELT"] ?: map["CEINTURE"] ?: "BLANCHE"
                val season = map["SEASON"] ?: map["SAISON"] ?: ""

                val group = if (birthDate.isNotBlank()) JudoGroup.fromBirthDate(birthDate) else null

                return ExtractedMemberInfo(
                    firstName = formatFirstName(firstName),
                    lastName = formatLastName(lastName),
                    birthDate = birthDate,
                    gender = gender,
                    phone = phone,
                    cardNumber = cardNumber,
                    qrCode = qr,
                    season = season,
                    group = group,
                    belt = Belt.fromString(beltStr),
                    rawScannedData = trimmed,
                    ocrRecognized = (firstName.isNotBlank() || lastName.isNotBlank() || birthDate.isNotBlank())
                )
            }
        }

        // 3. Simple QR Code or Card Number (e.g. 1, 42, 4521, JS-2026-001, JCS-1234)
        if (trimmed.startsWith("JCS-", ignoreCase = true) ||
            trimmed.startsWith("JS-", ignoreCase = true) ||
            trimmed.startsWith("CARD-", ignoreCase = true) ||
            (trimmed.length in 1..20 && trimmed.all { it.isDigit() || it == '-' || it == '_' })
        ) {
            val cleanNumber = trimmed
                .removePrefix("JCS-").removePrefix("jcs-")
                .removePrefix("JS-").removePrefix("js-")
                .removePrefix("CARD-").removePrefix("card-")
                .removePrefix("MEM-").removePrefix("mem-")
                .trim()
            return ExtractedMemberInfo(
                qrCode = cleanNumber,
                cardNumber = cleanNumber,
                rawScannedData = trimmed
            )
        }

        // 4. Free-form text / OCR Lines fallback
        val lines = trimmed.split("\n")
        return parseOcrText(trimmed, lines)
    }

    /**
     * Parses free-form text extracted by OCR from a physical document or sport card.
     * Extracts NOM, PRÉNOM, N°, DATE DE NAISSANCE, and TÉL. TUTEUR completely independently.
     * Works regardless of field order, single vs separate lines, or columnar layouts.
     */
    fun parseOcrText(fullText: String, lines: List<String>): ExtractedMemberInfo {
        var lastName = ""
        var firstName = ""
        var birthDate = ""
        var gender = ""
        var phone = ""
        var cardNumber = ""
        var season = ""

        val rawLines = if (lines.isNotEmpty()) lines else fullText.split("\n")
        val cleanLines = rawLines.map { it.trim() }.filter { it.isNotBlank() }

        // Lookahead stop pattern: stops value capture before any other subsequent label or line boundary
        val labelBoundaryPattern = """(?=\s*(?:(?i)(?<![A-Za-zÀ-ÖØ-öø-ÿ])(?:PR[EÉ]NOM|NOM|DATE(?:\s+DE)?\s+NAISSANCE|NAISSANCE|N[EÉ]\(?E?\)?\s*LE|T[EÉ]L(?:[EÉ]PHONE)?(?:\.?\s*TUTEUR|\.?\s*PARENT)?|SEXE|LICENCE|CARTE|SAISON)(?![A-Za-zÀ-ÖØ-öø-ÿ])|N[°o\.]\s*[:=\-.]?|$))"""

        for (i in cleanLines.indices) {
            val line = cleanLines[i]

            // --- SAISON (e.g. 2026-2027 or 2026/2027) ---
            if (season.isBlank()) {
                val seasonMatch = Regex("""(?i)\b(?:saison|season)?\s*[:=\-.]?\s*(20[2-3][0-9][\/\-]20[2-3][0-9])\b""").find(line)
                if (seasonMatch != null) {
                    season = seasonMatch.groupValues[1].trim()
                }
            }

            // --- N° CARTE / LICENCE (N°, N°:, N:, N°., N., NUMÉRO) ---
            if (cardNumber.isBlank()) {
                val cardMatch = Regex("""(?i)(?:^|[\s,;])(?:N[°o\.]|NUM[EÉ]RO(?:\s+DE\s+CARTE)?|LICENCE|CARTE|MATRICULE)\s*(?:N[°o\.]?)?\s*[:=\-.]?\s*([A-Za-z0-9\-]{1,20})$labelBoundaryPattern""").find(line)
                if (cardMatch != null) {
                    val candidate = cardMatch.groupValues[1].trim()
                    val isSeasonYear = candidate.matches(Regex("""20[2-3][0-9][\/\-]20[2-3][0-9]"""))
                    if (!isSeasonYear && !candidate.equals("SEDDOUK", ignoreCase = true) && !candidate.equals("JUDO", ignoreCase = true) && !candidate.equals("CLUB", ignoreCase = true)) {
                        cardNumber = candidate.removePrefix("JCS-").removePrefix("jcs-").removePrefix("JS-").removePrefix("js-")
                    } else if (isSeasonYear && season.isBlank()) {
                        season = candidate
                    }
                } else if (Regex("""(?i)^(?:N[°o\.]|NUM[EÉ]RO(?:\s+DE\s+CARTE)?|CARTE|LICENCE)\s*[:=\-.]?\s*$""").matches(line) && i + 1 < cleanLines.size) {
                    val nextLine = cleanLines[i + 1].trim()
                    if (nextLine.matches(Regex("""[A-Za-z0-9\-]{1,20}""")) && !isLabelLine(nextLine)) {
                        cardNumber = nextLine.removePrefix("JCS-").removePrefix("jcs-").removePrefix("JS-").removePrefix("js-")
                    }
                }
            }

            // Independent detection of NOM and PRÉNOM labels using Unicode-aware boundaries
            val hasNomLabel = Regex("""(?i)(?<![A-Za-zÀ-ÖØ-öø-ÿ])NOM(?:\s+DE\s+FAMILLE)?(?![A-Za-zÀ-ÖØ-öø-ÿ])""").containsMatchIn(line)
            val hasPrenomLabel = Regex("""(?i)(?<![A-Za-zÀ-ÖØ-öø-ÿ])PR[EÉ]NOM(?:\(S\))?(?![A-Za-zÀ-ÖØ-öø-ÿ])""").containsMatchIn(line)

            // --- BOTH NOM & PRÉNOM ON THE SAME LINE ---
            if (hasNomLabel && hasPrenomLabel) {
                // Check if NOM is first, then PRÉNOM
                val nomThenPrenom = Regex("""(?i)(?:^|[^A-Za-zÀ-ÖØ-öø-ÿ])NOM(?:\s+DE\s+FAMILLE)?\s*[:=\-.]?\s*([A-Za-zÀ-ÖØ-öø-ÿ\s\-']+?)\s+(?:(?<![A-Za-zÀ-ÖØ-öø-ÿ])PR[EÉ]NOM(?:\(S\))?)\s*[:=\-.]?\s*([A-Za-zÀ-ÖØ-öø-ÿ\s\-']+)$labelBoundaryPattern""").find(line)
                if (nomThenPrenom != null) {
                    val candLast = cleanExtractedName(nomThenPrenom.groupValues[1])
                    val candFirst = cleanExtractedName(nomThenPrenom.groupValues[2])
                    if (lastName.isBlank() && isValidName(candLast)) lastName = formatLastName(candLast)
                    if (firstName.isBlank() && isValidName(candFirst)) firstName = formatFirstName(candFirst)
                } else {
                    // Check if PRÉNOM is first, then NOM
                    val prenomThenNom = Regex("""(?i)(?:^|[^A-Za-zÀ-ÖØ-öø-ÿ])PR[EÉ]NOM(?:\(S\))?\s*[:=\-.]?\s*([A-Za-zÀ-ÖØ-öø-ÿ\s\-']+?)\s+(?:(?<![A-Za-zÀ-ÖØ-öø-ÿ])NOM(?:\s+DE\s+FAMILLE)?)\s*[:=\-.]?\s*([A-Za-zÀ-ÖØ-öø-ÿ\s\-']+)$labelBoundaryPattern""").find(line)
                    if (prenomThenNom != null) {
                        val candFirst = cleanExtractedName(prenomThenNom.groupValues[1])
                        val candLast = cleanExtractedName(prenomThenNom.groupValues[2])
                        if (firstName.isBlank() && isValidName(candFirst)) firstName = formatFirstName(candFirst)
                        if (lastName.isBlank() && isValidName(candLast)) lastName = formatLastName(candLast)
                    }
                }
            }

            // --- NOM DE FAMILLE (LAST NAME) INDEPENDENT EXTRACTION ---
            if (lastName.isBlank() && hasNomLabel) {
                val nomMatch = Regex("""(?i)(?:^|[^A-Za-zÀ-ÖØ-öø-ÿ])NOM(?:\s+DE\s+FAMILLE)?\s*[:=\-.]?\s*([A-Za-zÀ-ÖØ-öø-ÿ\s\-']+?)$labelBoundaryPattern""").find(line)
                if (nomMatch != null) {
                    val candidate = cleanExtractedName(nomMatch.groupValues[1])
                    if (isValidName(candidate)) {
                        lastName = formatLastName(candidate)
                    }
                }
                // If label was alone on the line, look ahead to the next non-label line
                if (lastName.isBlank() && Regex("""(?i)^(?:NOM(?:\s+DE\s+FAMILLE)?)\s*[:=\-.]?\s*$""").matches(line) && i + 1 < cleanLines.size) {
                    val nextLineCand = cleanExtractedName(cleanLines[i + 1])
                    if (isValidName(nextLineCand) && !isLabelLine(cleanLines[i + 1])) {
                        lastName = formatLastName(nextLineCand)
                    }
                }
            }

            // --- PRÉNOM (FIRST NAME) INDEPENDENT EXTRACTION ---
            if (firstName.isBlank() && hasPrenomLabel) {
                val prenomMatch = Regex("""(?i)(?:^|[^A-Za-zÀ-ÖØ-öø-ÿ])PR[EÉ]NOM(?:\(S\))?\s*[:=\-.]?\s*([A-Za-zÀ-ÖØ-öø-ÿ\s\-']+?)$labelBoundaryPattern""").find(line)
                if (prenomMatch != null) {
                    val candidate = cleanExtractedName(prenomMatch.groupValues[1])
                    if (isValidName(candidate)) {
                        firstName = formatFirstName(candidate)
                    }
                }
                // If label was alone on the line, look ahead to the next non-label line
                if (firstName.isBlank() && Regex("""(?i)^(?:PR[EÉ]NOM(?:\(S\))?)\s*[:=\-.]?\s*$""").matches(line) && i + 1 < cleanLines.size) {
                    val nextLineCand = cleanExtractedName(cleanLines[i + 1])
                    if (isValidName(nextLineCand) && !isLabelLine(cleanLines[i + 1])) {
                        firstName = formatFirstName(nextLineCand)
                    }
                }
            }

            // --- DATE DE NAISSANCE ---
            if (birthDate.isBlank()) {
                val dobMatch = Regex("""(?iu)(?:DATE\s*(?:DE)?\s*NAISSANCE|NAISSANCE|N[EÉEÉ]\(?E?\)?\s*(?:LE)?|D\.?O\.?B\.?|BIRTH\s*DATE)\s*[:=\-.]?\s*([0-9]{1,2}[\/\-\.][0-9]{1,2}[\/\-\.][0-9]{2,4}|[0-9]{4}[\/\-\.][0-9]{1,2}[\/\-\.][0-9]{1,2})""").find(line)
                if (dobMatch != null) {
                    val parsed = normalizeDateToIso(dobMatch.groupValues[1])
                    if (parsed.isNotBlank()) birthDate = parsed
                } else if (Regex("""(?iu)^(?:DATE\s*(?:DE)?\s*NAISSANCE|NAISSANCE|N[EÉEÉ]\(?E?\)?\s*(?:LE)?|D\.?O\.?B\.?|BIRTH\s*DATE)\s*[:=\-.]?\s*$""").matches(line) && i + 1 < cleanLines.size) {
                    val nextLine = cleanLines[i + 1]
                    val dateMatch = Regex("""\b([0-9]{1,2}[\/\-\.][0-9]{1,2}[\/\-\.][0-9]{2,4}|[0-9]{4}[\/\-\.][0-9]{1,2}[\/\-\.][0-9]{1,2})\b""").find(nextLine)
                    if (dateMatch != null) {
                        val parsed = normalizeDateToIso(dateMatch.groupValues[1])
                        if (parsed.isNotBlank()) birthDate = parsed
                    }
                }
            }

            // --- TÉL. TUTEUR / CONTACT ---
            if (phone.isBlank()) {
                val telMatch = Regex("""(?iu)(?:T[EÉEÉ]L\.?(?:\s*TUTEUR|\s*PARENT|\s*[EÉEÉ]L[EÈEÈ]VE)?|T[EÉEÉ]L[EÉEÉ]PHONE(?:\s*(?:DU\s*)?TUTEUR|\s*PARENT)?|CONTACT|MOBILE|GSM|PHONE)\s*[:=\-.]?\s*([+0-9\s\.\-]{8,18})""").find(line)
                if (telMatch != null) {
                    val cleaned = cleanPhoneNumber(telMatch.groupValues[1])
                    if (cleaned.length >= 8) {
                        phone = cleaned
                    }
                } else if (Regex("""(?iu)^(?:T[EÉEÉ]L\.?(?:\s*TUTEUR|\s*PARENT)?|T[EÉEÉ]L[EÉEÉ]PHONE(?:\s*(?:DU\s*)?TUTEUR|\s*PARENT)?)\s*[:=\-.]?\s*$""").matches(line) && i + 1 < cleanLines.size) {
                    val nextLine = cleanLines[i + 1]
                    val phoneMatch = Regex("""([+0-9\s\.\-]{8,18})""").find(nextLine)
                    if (phoneMatch != null) {
                        val cleaned = cleanPhoneNumber(phoneMatch.groupValues[1])
                        if (cleaned.length >= 8) phone = cleaned
                    }
                }
            }

            // --- SEXE (GENDER) ---
            if (gender.isBlank()) {
                val sexeMatch = Regex("""(?iu)\b(?:sexe|gender|genre)\s*[:=\-.]?\s*(masculin|f[eéEÉ]minin|homme|femme|[mfMF])\b""").find(line)
                if (sexeMatch != null) {
                    gender = normalizeGender(sexeMatch.groupValues[1])
                }
            }
        }

        // Secondary Pass: Block / Columnar layout (labels block followed by values block)
        if (lastName.isBlank() || firstName.isBlank() || birthDate.isBlank() || phone.isBlank() || cardNumber.isBlank()) {
            parseBlockLayout(cleanLines)?.let { colInfo ->
                if (lastName.isBlank() && colInfo.lastName.isNotBlank()) lastName = colInfo.lastName
                if (firstName.isBlank() && colInfo.firstName.isNotBlank()) firstName = colInfo.firstName
                if (birthDate.isBlank() && colInfo.birthDate.isNotBlank()) birthDate = colInfo.birthDate
                if (phone.isBlank() && colInfo.phone.isNotBlank()) phone = colInfo.phone
                if (cardNumber.isBlank() && colInfo.cardNumber.isNotBlank()) cardNumber = colInfo.cardNumber
            }
        }

        // Tertiary Pass: Standalone Birth Date anywhere in document text if not found yet
        if (birthDate.isBlank()) {
            val standaloneDateRegex = Regex("""\b(0[1-9]|[12][0-9]|3[01])[\/\-\.](0[1-9]|1[0-2])[\/\-\.](19[5-9][0-9]|20[0-2][0-9])\b""")
            val match = standaloneDateRegex.find(fullText)
            if (match != null) {
                birthDate = normalizeDateToIso(match.value)
            }
        }

        // Tertiary Pass: Standalone Tutor Phone anywhere in document text if not found yet
        if (phone.isBlank()) {
            val standalonePhoneRegex = Regex("""\b(0[1-9](?:[\s\.\-]?[0-9]{2}){4}|0[567][0-9]{8}|\+?(?:33|213)[\s\.\-]?[0-9\s\.\-]{8,12})\b""")
            val match = standalonePhoneRegex.find(fullText)
            if (match != null) {
                val cleaned = cleanPhoneNumber(match.value)
                if (cleaned.length >= 8) phone = cleaned
            }
        }

        // Suggested Judo category based on birth date
        val group = if (birthDate.isNotBlank()) JudoGroup.fromBirthDate(birthDate) else null
        val qrCode = if (cardNumber.isNotBlank()) "JCS-$cardNumber" else ""

        val recognizedAny = firstName.isNotBlank() || lastName.isNotBlank() || birthDate.isNotBlank() || phone.isNotBlank() || cardNumber.isNotBlank()

        return ExtractedMemberInfo(
            firstName = firstName,
            lastName = lastName,
            birthDate = birthDate,
            gender = gender,
            phone = phone,
            cardNumber = cardNumber,
            qrCode = qrCode,
            season = season,
            group = group,
            rawScannedData = fullText,
            ocrRecognized = recognizedAny
        )
    }

    private fun isLabelLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.matches(Regex("""(?iu)^(?:N[°o\.]|N[UÚ]M[EÉ]RO|CARTE|LICENCE|NOM|PR[EÉEÉ]NOM|DATE(?:\s+DE)?\s+NAISSANCE|NAISSANCE|T[EÉEÉ]L\.?(?:\s*TUTEUR|\s*PARENT)?|SEXE|SAISON|JUDO\s*CLUB\s*SEDDOUK)\s*[:=\-.]?\s*$"""))
            || trimmed.contains(Regex("""(?iu)\b(?:nom|pr[eéEÉ]nom|date\s+de\s+naissance|t[eéEÉ]l\.?\s*tuteur|licence|saison)\s*[:=\-.]"""))
    }

    private fun cleanExtractedName(raw: String): String {
        return raw
            .replace(Regex("""(?iu)\b(?:pr[eéEÉ]nom|nom|date|naissance|t[eéEÉ]l|tuteur|sexe|licence|carte|n°|n:)\b.*$"""), "")
            .replace(Regex("""^[:=\-\s,.]+"""), "")
            .replace(Regex("""[:=\-\s,.]+$"""), "")
            .replace(Regex("""[^A-Za-zÀ-ÖØ-öø-ÿ\s\-']"""), "")
            .trim()
    }

    private fun parseBlockLayout(lines: List<String>): ExtractedMemberInfo? {
        val labelTokens = mutableListOf<String>()
        var lastLabelIdx = -1

        for (i in lines.indices) {
            val l = lines[i].trim()
            if (Regex("""(?iu)^N[°o\.]\s*[:=\-.]?$""").matches(l)) {
                labelTokens.add("CARD")
                lastLabelIdx = i
            } else if (Regex("""(?iu)^NOM(?:\s+DE\s+FAMILLE)?\s*[:=\-.]?$""").matches(l)) {
                labelTokens.add("NOM")
                lastLabelIdx = i
            } else if (Regex("""(?iu)^PR[EÉEÉ]NOM(?:\(S\))?\s*[:=\-.]?$""").matches(l)) {
                labelTokens.add("PRENOM")
                lastLabelIdx = i
            } else if (Regex("""(?iu)^(?:DATE\s*(?:DE)?\s*NAISSANCE|NAISSANCE)\s*[:=\-.]?$""").matches(l)) {
                labelTokens.add("DOB")
                lastLabelIdx = i
            } else if (Regex("""(?iu)^(?:T[EÉEÉ]L\.?(?:\s*TUTEUR|\s*PARENT)?)\s*[:=\-.]?$""").matches(l)) {
                labelTokens.add("PHONE")
                lastLabelIdx = i
            }
        }

        if (labelTokens.size < 2 || lastLabelIdx >= lines.size - 1) return null

        val values = mutableListOf<String>()
        for (i in (lastLabelIdx + 1) until lines.size) {
            val v = lines[i].trim()
            if (v.isNotBlank() && !isLabelLine(v) && !BLACKLIST_NAME_WORDS.contains(v.uppercase(Locale.ROOT))) {
                values.add(v)
            }
        }

        if (values.size >= labelTokens.size) {
            var colLast = ""
            var colFirst = ""
            var colDob = ""
            var colPhone = ""
            var colCard = ""

            for (k in labelTokens.indices) {
                val lab = labelTokens[k]
                val valStr = values[k]
                when (lab) {
                    "NOM" -> if (isValidName(valStr)) colLast = formatLastName(valStr)
                    "PRENOM" -> if (isValidName(valStr)) colFirst = formatFirstName(valStr)
                    "CARD" -> colCard = valStr.removePrefix("JCS-").removePrefix("JS-")
                    "DOB" -> colDob = normalizeDateToIso(valStr)
                    "PHONE" -> colPhone = cleanPhoneNumber(valStr)
                }
            }

            return ExtractedMemberInfo(
                lastName = colLast,
                firstName = colFirst,
                birthDate = colDob,
                phone = colPhone,
                cardNumber = colCard,
                qrCode = if (colCard.isNotBlank()) "JCS-$colCard" else ""
            )
        }
        return null
    }

    /**
     * Merge information extracted from QR / Barcode and Document OCR with cropped portrait.
     * Identity fields (Nom, Prénom, Date de Naissance, Tél. Tuteur) prioritize OCR recognition.
     * Card number prioritizes QR code or OCR N°.
     * Cropped portrait is strictly applied (never the whole document).
     */
    fun mergeInfo(
        qrInfo: ExtractedMemberInfo?,
        ocrInfo: ExtractedMemberInfo?,
        croppedPortraitPath: String,
        portraitDetected: Boolean
    ): ExtractedMemberInfo {
        val ocr = ocrInfo ?: ExtractedMemberInfo()
        val qr = qrInfo ?: ExtractedMemberInfo()

        // Prioritize OCR for human identity fields printed on document
        val firstName = if (ocr.firstName.isNotBlank()) ocr.firstName else qr.firstName
        val lastName = if (ocr.lastName.isNotBlank()) ocr.lastName else qr.lastName
        val birthDate = if (ocr.birthDate.isNotBlank()) ocr.birthDate else qr.birthDate
        val gender = if (ocr.gender.isNotBlank()) ocr.gender else qr.gender
        val phone = if (ocr.phone.isNotBlank()) ocr.phone else qr.phone

        // Card number: take from QR if present, otherwise OCR N°
        val cardNumber = if (qr.cardNumber.isNotBlank()) qr.cardNumber else ocr.cardNumber
        val qrCode = if (qr.qrCode.isNotBlank()) qr.qrCode else if (cardNumber.isNotBlank()) "JCS-$cardNumber" else ocr.qrCode
        val season = if (ocr.season.isNotBlank()) ocr.season else qr.season

        val group = if (birthDate.isNotBlank()) JudoGroup.fromBirthDate(birthDate)
            else ocr.group ?: qr.group

        val raw = listOfNotNull(
            qrInfo?.rawScannedData.takeIf { !it.isNullOrBlank() }?.let { "QR: $it" },
            ocrInfo?.rawScannedData.takeIf { !it.isNullOrBlank() }?.let { "OCR: $it" }
        ).joinToString("\n\n")

        return ExtractedMemberInfo(
            firstName = firstName,
            lastName = lastName,
            birthDate = birthDate,
            gender = gender,
            phone = phone,
            cardNumber = cardNumber,
            qrCode = qrCode,
            season = season,
            photoUrl = croppedPortraitPath,
            group = group,
            belt = qr.belt.takeIf { it != Belt.BLANCHE } ?: ocr.belt,
            rawScannedData = raw,
            portraitDetected = portraitDetected,
            ocrRecognized = (firstName.isNotBlank() || lastName.isNotBlank() || birthDate.isNotBlank() || phone.isNotBlank())
        )
    }

    private fun sanitizeName(raw: String): String {
        return raw
            .replace(Regex("""^[:=\-\s,.]+"""), "")
            .replace(Regex("""[:=\-\s,.]+$"""), "")
            .replace(Regex("""[^A-Za-zÀ-ÖØ-öø-ÿ\s\-']"""), "")
            .trim()
    }

    fun isValidName(text: String): Boolean {
        val clean = text.replace(Regex("""[^A-Za-zÀ-ÖØ-öø-ÿ\s\-]"""), "").trim()
        if (clean.length < 2) return false
        val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        return words.none { it.uppercase(Locale.ROOT) in BLACKLIST_NAME_WORDS }
    }

    fun formatLastName(raw: String): String {
        return sanitizeName(raw).uppercase(Locale.ROOT)
    }

    fun formatFirstName(raw: String): String {
        val clean = sanitizeName(raw)
        return clean.split(Regex("""[\s\-]+""")).filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
            }
    }

    fun normalizeGender(raw: String): String {
        val clean = raw.trim().uppercase(Locale.ROOT)
        return when {
            clean.startsWith("M") || clean.startsWith("H") -> "M"
            clean.startsWith("F") -> "F"
            else -> ""
        }
    }

    fun cleanPhoneNumber(raw: String): String {
        return raw.filter { it.isDigit() || it == '+' }
    }

    fun normalizeDateToIso(raw: String): String {
        val clean = raw.trim().replace(".", "/").replace("-", "/")
        val parts = clean.split("/")
        if (parts.size == 3) {
            val p0 = parts[0].padStart(2, '0')
            val p1 = parts[1].padStart(2, '0')
            val p2 = parts[2]
            return try {
                if (p0.length == 4) {
                    // YYYY/MM/DD
                    "$p0-$p1-${p2.padStart(2, '0')}"
                } else if (p2.length == 4) {
                    // DD/MM/YYYY
                    "$p2-$p1-$p0"
                } else if (p2.length == 2) {
                    // 2-digit year DD/MM/YY
                    val yearNum = p2.toIntOrNull() ?: return ""
                    val fullYear = if (yearNum > 30) 1900 + yearNum else 2000 + yearNum
                    "$fullYear-$p1-$p0"
                } else ""
            } catch (_: Exception) {
                ""
            }
        }
        return ""
    }
}

