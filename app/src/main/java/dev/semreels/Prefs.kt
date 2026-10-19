package dev.semreels

import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo

const val INSTAGRAM_PACKAGE = "com.instagram.android"

object Prefs {
    const val FILE = "semreels"

    const val ENABLED = "enabled"
    const val HIDE_REELS = "hide_reels"
    const val HIDE_SEARCH = "hide_search"
    const val BLOCK_REELS = "block_reels"
    const val BLOCK_SEARCH = "block_search"
    const val BLOCK_SHARED_REELS = "block_shared_reels"
    const val SKIP_STORY_ADS = "skip_story_ads"
    const val SKIP_REEL_ADS = "skip_reel_ads"
    const val AD_LABELS = "ad_labels"
    const val TAB_BAR_IDS = "tab_bar_ids"
    const val REELS_IDS = "reels_ids"
    const val REELS_DESCS = "reels_descs"
    const val SEARCH_IDS = "search_ids"
    const val SEARCH_DESCS = "search_descs"
    const val DUMP_REQUESTED = "dump_requested"
    const val LAST_STATUS = "last_status"

    // Valores do Instagram hoje. Quando uma atualização mudar algo,
    // ajuste pela tela do app (sem recompilar) usando o diagnóstico.
    const val DEFAULT_TAB_BAR_IDS = "com.instagram.android:id/tab_bar"
    const val HOME_TAB_ID = "com.instagram.android:id/feed_tab"
    const val CLIPS_VIEWER_ID = "com.instagram.android:id/clips_viewer_view_pager"
    const val MAIN_PAGER_ID = "com.instagram.android:id/swipeable_tab_view_pager"
    const val STORY_HEADER_ID = "com.instagram.android:id/reel_viewer_header"
    const val DEFAULT_REELS_IDS = "com.instagram.android:id/clips_tab"
    const val DEFAULT_REELS_DESCS = "Reels"
    const val DEFAULT_SEARCH_IDS = "com.instagram.android:id/search_tab"
    const val DEFAULT_SEARCH_DESCS = "Pesquisar e explorar, Search and explore"
    const val DEFAULT_AD_LABELS = "Patrocinado, Sponsored, Publicidade, Anúncio, Ad"

    fun list(raw: String?): List<String> =
        raw.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Reconhece uma aba da barra pelo ID da view ou pela descrição de acessibilidade. */
data class TabSelector(val ids: List<String>, val descriptions: List<String>) {
    fun matches(node: AccessibilityNodeInfo): Boolean {
        if (node.viewIdResourceName in ids) return true
        val label = node.contentDescription?.toString()?.trim() ?: return false
        return descriptions.any { it.equals(label, ignoreCase = true) }
    }
}

data class BarConfig(
    val enabled: Boolean,
    val tabBarIds: List<String>,
    val reels: TabSelector,
    val search: TabSelector,
    val hideReels: Boolean,
    val hideSearch: Boolean,
    val blockReels: Boolean,
    val blockSearch: Boolean,
    val blockSharedReels: Boolean,
    val skipStoryAds: Boolean,
    val skipReelAds: Boolean,
    val adLabels: List<String>,
) {
    val active get() = enabled &&
        (hideReels || hideSearch || blockReels || blockSearch || blockSharedReels || skipStoryAds || skipReelAds)

    fun isHidden(tab: AccessibilityNodeInfo) =
        (hideReels && reels.matches(tab)) || (hideSearch && search.matches(tab))

    // Aba selecionada é Reels ou Pesquisa: a pessoa chegou lá (inclusive arrastando o feed).
    fun isBlocked(tab: AccessibilityNodeInfo) =
        (blockReels && reels.matches(tab)) || (blockSearch && search.matches(tab))

    companion object {
        fun from(p: SharedPreferences) = BarConfig(
            enabled = p.getBoolean(Prefs.ENABLED, true),
            tabBarIds = Prefs.list(p.getString(Prefs.TAB_BAR_IDS, Prefs.DEFAULT_TAB_BAR_IDS)),
            reels = TabSelector(
                Prefs.list(p.getString(Prefs.REELS_IDS, Prefs.DEFAULT_REELS_IDS)),
                Prefs.list(p.getString(Prefs.REELS_DESCS, Prefs.DEFAULT_REELS_DESCS)),
            ),
            search = TabSelector(
                Prefs.list(p.getString(Prefs.SEARCH_IDS, Prefs.DEFAULT_SEARCH_IDS)),
                Prefs.list(p.getString(Prefs.SEARCH_DESCS, Prefs.DEFAULT_SEARCH_DESCS)),
            ),
            hideReels = p.getBoolean(Prefs.HIDE_REELS, true),
            hideSearch = p.getBoolean(Prefs.HIDE_SEARCH, true),
            blockReels = p.getBoolean(Prefs.BLOCK_REELS, false),
            blockSearch = p.getBoolean(Prefs.BLOCK_SEARCH, false),
            blockSharedReels = p.getBoolean(Prefs.BLOCK_SHARED_REELS, false),
            skipStoryAds = p.getBoolean(Prefs.SKIP_STORY_ADS, false),
            skipReelAds = p.getBoolean(Prefs.SKIP_REEL_ADS, false),
            adLabels = Prefs.list(p.getString(Prefs.AD_LABELS, Prefs.DEFAULT_AD_LABELS)),        )
    }
}
