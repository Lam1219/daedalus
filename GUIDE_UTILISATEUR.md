# Guide d'utilisation — Daedalus Watch & Companion

## Table des matières

1. [Prérequis](#1-prérequis)
2. [Installation de l'application montre (Wear OS)](#2-installation-de-lapplication-montre-wear-os)
3. [Installation de l'application compagnon (Android)](#3-installation-de-lapplication-compagnon-android)
4. [Première utilisation — Phase 0 (Pilote fondateur)](#4-première-utilisation--phase-0-pilote-fondateur)
5. [Connexion de la ceinture thoracique Cycplus H2](#5-connexion-de-la-ceinture-thoracique-cycplus-h2)
6. [Déroulement d'une session](#6-déroulement-dune-session)
7. [Boutons et événements](#7-boutons-et-événements)
8. [Moteur haptique (vibrations)](#8-moteur-haptique-vibrations)
9. [Export des données vers l'ordinateur](#9-export-des-données-vers-lordinateur)
10. [Dépannage](#10-dépannage)

---

## 1. Prérequis

### Matériel
- **Montre :** Samsung Galaxy Watch5 Pro (R920)
- **Téléphone :** Android 8.0 ou supérieur
- **Ceinture thoracique :** Cycplus H2 Heart Rate (capteur de fréquence cardiaque avec intervals RR)
- **Ordinateur :** pour le traitement des données avec la bibliothèque Python `daedalus_watch_kit`

### Logiciel
- Application **Daedalus Watch** (installée sur la montre)
- Application **Daedalus Companion** (installée sur le téléphone)
- Application **Polar Beat** (optionnelle, pour l'export CSV de la ceinture vers l'ordinateur)

---

## 2. Installation de l'application montre (Wear OS)

### Via Android Studio (développement)

1. Ouvrez **Android Studio**.
2. Allez dans **File → Open** et sélectionnez le dossier `daedalus-watch`.
3. Laissez Gradle synchroniser les dépendances (premier chargement peut prendre plusieurs minutes).
4. Connectez votre montre en mode développeur :
   - Sur la montre : **Paramètres → À propos → Version logiciel** → tapez 7 fois pour activer le mode développeur.
   - Activez le **débogage ADB** et le **débogage via Wi-Fi**.
5. Dans Android Studio, sélectionnez **daedalus-watch** comme module d'exécution et cliquez sur **Run ▶**.

### Via ADB (fichier APK)

```bash
adb -e install daedalus-watch/app/build/outputs/apk/debug/app-debug.apk
```

L'icône **Daedalus Watch** apparaîtra dans le lanceur d'applications de la montre.

---

## 3. Installation de l'application compagnon (Android)

### Via Android Studio (développement)

1. Allez dans **File → Open** et sélectionnez le dossier `daedalus-companion`.
2. Laissez Gradle synchroniser les dépendances.
3. Connectez votre téléphone Android via USB.
4. Sélectionnez **daedalus-companion** comme module d'exécution et cliquez sur **Run ▶**.

### Via ADB (fichier APK)

```bash
adb install daedalus-companion/app/build/outputs/apk/debug/app-debug.apk
```

L'icône **Daedalus Companion** apparaîtra sur votre téléphone.

---

## 4. Première utilisation — Phase 0 (Pilote fondateur)

La Phase 0 est un test unique (N=1) pour valider le pipeline de données. Voici les étapes :

1. **Chargez la montre** à au moins 80 % avant la session.
2. **Placez la montre** sur le poignet non dominant (sauf indication contraire).
3. **Installez la ceinture thoracique** Cycplus H2 selon les instructions du fabricant.
4. **Ouvrez l'application compagnon** sur le téléphone.
5. **Connectez la ceinture** via Bluetooth (voir section 5).
6. **Lancez l'application montre** Daedalus Watch.
7. **Appuyez sur ▶ Start Recording** sur la montre (ou sur le téléphone).
8. La collecte de données commence. Les capteurs enregistrent en continu.
9. **Appuyez sur ⏹ Stop Recording** pour terminer la session.
10. **Exportez les données** depuis l'application compagnon (voir section 9).

---

## 5. Connexion de la ceinture thoracique Cycplus H2

### Depuis l'application compagnon

1. Ouvrez **Daedalus Companion** sur votre téléphone.
2. Appuyez sur **Connect Chest Strap (BLE)**.
3. L'application demande les permissions Bluetooth — acceptez-les.
4. L'application scanne les appareils BLE à proximité.
5. Lorsque la ceinture Cycplus H2 est détectée, elle se connecte automatiquement.
6. Le statut passe à **CONNECTED** et les données de fréquence cardiaque apparaissent :
   - **HR :** battements par minute (bpm)
   - **RR :** intervalle entre battements (ms)
   - **Nombre d'échantillons** collectés

### Si la connexion échoue

- Vérifiez que la ceinture est bien portée et humidifiée.
- Assurez-vous que le Bluetooth est activé sur le téléphone.
- Redémarrez l'application et réessayez.
- La ceinture peut apparaître sous un nom générique comme "H2" ou "Heart Rate" au lieu de "Cycplus".

---

## 6. Déroulement d'une session

### Écran de la montre

```
┌─────────────────────────┐
│  Daedalus Watch         │
│  Status: RECORDING      │
│  imu_available: true    │
│  collecting: true       │
│                         │
│  ▶ Start Recording      │
│  ⏹ Stop Recording       │
│  🔔 Sync Taps           │
│  👏 Sync Claps          │
│  ⚡ Haptic Test         │
└─────────────────────────┘
```

### Écran du téléphone

```
┌──────────────────────────────────┐
│  Daedalus Companion              │
│  Chest strap: CONNECTED          │
│  HR: 72 bpm  RR: 830 ms          │
│  Session: sub-001/sess-042       │
│  State: ACTIVE                   │
│                                  │
│  [▶ Start]    [⏹ Stop]          │
│  [📦 Export Session]             │
│                                  │
│  Event Markers                   │
│  [🧘 Rest]  [🧠 Stress] [👏 Sync]│
└──────────────────────────────────┘
```

### Synchronisation des horloges

Avant chaque session, effectuez les événements de synchronisation suivants :

1. **Sync Taps** : tapez la montre contre la ceinture thoracique **3 fois**.
2. **Sync Claps** : applaudissez **5 fois** pendant que les deux appareils enregistrent.

Ces événements créent des pics détectables dans les données de la montre, de la ceinture et du téléphone, permettant de synchroniser les horloges avec une erreur < 100 ms.

---

## 7. Boutons et événements

### Sur la montre

| Bouton | Action | Événement enregistré |
|--------|--------|---------------------|
| **▶ Start Recording** | Lance l'enregistrement des capteurs | `SESSION_START` |
| **⏹ Stop Recording** | Arrête l'enregistrement, ferme les fichiers | `SESSION_END` |
| **🔔 Sync Taps** | Marque l'événement de synchronisation par tapotement | `SYNC_TAPS` |
| **👏 Sync Claps** | Marque l'événement de synchronisation par applaudissements | `SYNC_CLAPS` |
| **⚡ Haptic Test** | Envoie une impulsion vibrante et mesure la latence | `HAPTIC_LATENCY_TEST` |

### Sur le téléphone

| Bouton | Action | Événement enregistré |
|--------|--------|---------------------|
| **▶ Start** | Lance la session + demande à la montre de démarrer | `SESSION_START` |
| **⏹ Stop** | Termine la session + demande à la montre de stopper | `SESSION_END` |
| **🧘 Rest** | Marque le début de la phase de repos | `REST_START` |
| **🧠 Stress Task** | Marque le début du test de stress cognitif | `STRESS_TASK_START` |
| **👏 Sync (Claps)** | Marque l'événement de synchronisation | `SYNC_CLAPS` |
| **📦 Export Session** | Crée un fichier zip de la session | — |

### Synchronisation automatique

Les événements marqués sur le téléphone sont automatiquement envoyés à la montre via l'API DataLayer de Wear OS. Inversement, les événements de la montre sont reçus par le téléphone. Les deux appareils partagent donc les mêmes horodatages d'événements.

---

## 8. Moteur haptique (vibrations)

L'application montre inclut un moteur haptique pour délivrer des stimuli vibrants synchronisés.

### Patterns disponibles

| Pattern | Description | Utilisation |
|---------|-------------|-------------|
| **Gait Metronome** | Impulsions rythmiques à la cadence cible | Synchronisation de la marche |
| **Breathing Pacing** | Impulsion longue (inspire 4s) puis courte (expire 6s) | Respiration guidée, 6 cycles/min |
| **Tremor Grounding** | Impulsions douces à 2-3 Hz | Exploratoire, non thérapeutique |
| **Latency Test** | Impulsion unique avec horodatage | Mesure de la latence de vibration |

### Test de latence

1. Appuyez sur **⚡ Haptic Test** sur la montre.
2. La montre enregistre l'horodatage de déclenchement (`trigger_ms`).
3. Simultanément, enregistrez avec la caméra du téléphone pour capturer le moment où la vibration est ressentie.
4. **Latence = onset_ms - trigger_ms**
5. **Objectif : < 150 ms, gigue < 30 ms**

---

## 9. Export des données vers l'ordinateur

### Depuis l'application compagnon

1. Après avoir terminé une session (⏹ Stop), appuyez sur **📦 Export Session**.
2. L'application crée un fichier zip contenant tous les fichiers de la session.
3. Le fichier est sauvegardé dans :
   ```
   /Android/data/com.medicina.daedaluscompanion/files/daedalus/exports/
   daedalus_sub-001_sess-001_YYYYMMDD_HHMMSS.zip
   ```

### Contenu du fichier zip

```
daedalus_sub-001_sess-001_20260101_120000/
├── session_metadata.json     ← Métadonnées de la session
├── phone_events.csv          ← Événements enregistrés sur le téléphone
├── watch_imu.csv             ← Données IMU de la montre (accéléromètre + gyroscope)
├── watch_hr.csv              ← Données de fréquence cardiaque de la montre
├── watch_haptics.csv         ← Événements haptiques de la montre
└── ecg/
    └── ecg_reference.csv     ← Intervals RR de la ceinture Cycplus H2
```

### Transfert vers l'ordinateur

1. Connectez le téléphone à l'ordinateur via USB.
2. Copiez le fichier zip depuis le dossier `exports/`.
3. Ou utilisez `adb pull` :
   ```bash
   adb pull /Android/data/com.medicina.daedaluscompanion/files/daedalus/exports/ ./
   ```

### Traitement avec la bibliothèque Python

```python
from daedalus_watch_kit.ingestion import ingest_session
from pathlib import Path

# Dézippez d'abord le fichier
# Puis chargez la session
data = ingest_session(Path("data/raw/sub-001/sess-001"))

# Rapport de complétude
print(data["completeness"])

# Synchronisation, fenêtrage, qualité du signal, etc.
# Voir la documentation de daedalus_watch_kit
```

---

## 10. Dépannage

### La montre ne détecte pas les capteurs IMU

- **Symptôme :** `imu_available: false` sur l'écran de débogage.
- **Solution :** Redémarrez la montre. Les capteurs IMU sont généralement toujours disponibles sur la Watch5 Pro.

### La fréquence cardiaque ne s'affiche pas

- **Symptôme :** Pas de données HR dans `watch_hr.csv`.
- **Solution :**
  1. Vérifiez que l'application a la permission **BODY_SENSORS**.
  2. Assurez-vous que la montre est bien ajustée au poignet.
  3. Redémarrez le service de capteurs (Stop → Start).

### La ceinture Cycplus H2 ne se connecte pas

- **Symptôme :** Le statut reste sur `SCANNING` ou `ERROR`.
- **Solution :**
  1. Vérifiez que la ceinture est portée et humidifiée.
  2. Vérifiez que le Bluetooth est activé sur le téléphone.
  3. Assurez-vous que les permissions Bluetooth sont accordées.
  4. Redémarrez le téléphone et la ceinture.
  5. La ceinture peut apparaître sous un nom différent ("H2", "Heart Rate", etc.).

### Les données de la montre ne sont pas reçues par le téléphone

- **Symptôme :** Les fichiers `watch_imu.csv`, `watch_hr.csv` sont vides sur le téléphone.
- **Solution :**
  1. Vérifiez que la montre et le téléphone sont connectés via Wear OS.
  2. Sur le téléphone, ouvrez l'application **Wear OS** et vérifiez la connexion.
  3. Redémarrez les deux appareils et réessayez.

### La batterie se vide trop vite

- **Symptôme :** > 20 % de batterie pour une session de 60 minutes.
- **Solution :**
  1. Réduisez la fréquence d'échantillonnage IMU (actuellement 50 Hz).
  2. Désactivez le GPS sur la montre pendant la session.
  3. Fermez les autres applications en arrière-plan.
  4. Réduisez la luminosité de l'écran de la montre.

### L'export zip échoue

- **Symptôme :** Aucun fichier zip créé après avoir appuyé sur Export.
- **Solution :**
  1. Vérifiez qu'une session a été démarrée et arrêtée correctement.
  2. Vérifiez que le téléphone a de l'espace de stockage disponible.
  3. Sur Android 10+, l'accès au stockage externe peut être restreint — utilisez le dossier interne de l'application.

---

## Annexe A : Schéma des données

Les fichiers CSV suivent exactement le schéma de la bibliothèque Python `daedalus_watch_kit` :

### `watch_imu.csv`

| Colonne | Type | Description |
|---------|------|-------------|
| `epoch_ms` | int64 | Horodatage Unix en millisecondes |
| `elapsed_ms` | int64 | Temps écoulé depuis le démarrage du capteur |
| `ax`, `ay`, `az` | float | Accélération (m/s²) |
| `gx`, `gy`, `gz` | float | Vitesse angulaire (rad/s) |
| `motion_intensity` | float | Intensité du mouvement (optionnel) |

### `watch_hr.csv`

| Colonne | Type | Description |
|---------|------|-------------|
| `epoch_ms` | int64 | Horodatage Unix en millisecondes |
| `hr_bpm` | float | Fréquence cardiaque (battements/min) |
| `hrv_rmssd_ms` | float | RMSSD (si disponible) |
| `hr_quality` | float | Qualité du signal (si disponible) |
| `source` | string | Source : `samsung_health` ou `sensor` |

### `ecg_reference.csv` (Cycplus H2)

| Colonne | Type | Description |
|---------|------|-------------|
| `epoch_ms` | int64 | Horodatage Unix en millisecondes |
| `ecg_raw` | float | Toujours vide (pas de forme d'onde brute) |
| `rr_ms` | float | Intervalle RR (ms) |
| `hr_bpm` | float | Fréquence cardiaque dérivée |
| `beat_quality` | float | Indicateur de qualité |

### `phone_events.csv`

| Colonne | Type | Description |
|---------|------|-------------|
| `epoch_ms` | int64 | Horodatage Unix en millisecondes |
| `event_name` | string | Nom de l'événement (ex: `REST_START`) |
| `source` | string | `manual` ou `auto` |
| `notes` | string | Notes optionnelles |

---

## Annexe B : Vocabulaire

| Terme | Définition |
|-------|-----------|
| **IMU** | Unité de mesure inertielle (accéléromètre + gyroscope) |
| **RR** | Intervalle entre deux battements cardiaques consécutifs |
| **HRV** | Variabilité de la fréquence cardiaque |
| **BLE** | Bluetooth Low Energy |
| **DataLayer** | API de communication entre montre Wear OS et téléphone |
| **Sync Taps** | Tapotements de synchronisation (3×) |
| **Sync Claps** | Applaudissements de synchronisation (5×) |
| **Phase 0** | Pilote fondateur (N=1) pour valider le pipeline |
| **Phase 1** | Cohorte de contrôles sains (N=10-20) |
| **Haptique** | Relatif au toucher / vibrations |

---

*Daedalus — Prototype de recherche et bien-être. Ne prétend pas traiter, diagnostiquer ou prévenir une maladie.*
