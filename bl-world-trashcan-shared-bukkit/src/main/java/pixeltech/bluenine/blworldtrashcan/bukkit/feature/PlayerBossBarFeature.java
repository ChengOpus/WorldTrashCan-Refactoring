package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import pixeltech.bluenine.blworldtrashcan.bukkit.notify.BossBarPreferenceService;

/** 普通平台与 Folia 共用的个人 BossBar 设置入口。 */
public interface PlayerBossBarFeature extends Feature {
    BossBarPreferenceService bossBarPreferences();
}
