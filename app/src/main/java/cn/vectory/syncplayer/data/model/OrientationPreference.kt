package cn.vectory.syncplayer.data.model

/**
 * Persisted player orientation preference stored in DataStore.
 *
 * This is the **persistent** user preference that survives app restarts.
 * It controls how the player chooses its display orientation when starting a video.
 *
 * **Do not confuse with [cn.vectory.syncplayer.player.OrientationMode].**
 * - [OrientationPreference] = what the user chose in Settings (persisted, drives behaviour).
 * - [cn.vectory.syncplayer.player.OrientationMode] = momentary toggle state shown in the
 *   player overlay (`FULLSCREEN` / `ROTATION`), used only for the
 *   in-player orientation toggle pill display.
 *
 * The two enums happen to share the concept of "orientation" but serve completely
 * different roles in different layers of the app.
 */
enum class OrientationPreference {
    FOLLOW_VIDEO,
    FOLLOW_SYSTEM
}
