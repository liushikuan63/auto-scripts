package com.farmbot.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 GameSceneDetector.classifyFromText 在大厅文案下不会误判为农场。
 *
 * 背景：v1.4.15 曾把 confirmFarmScene 标志词放宽为"浇水/成熟/作物/土地"等宽泛词，
 * 导致大厅界面（混排"王者农场 周末双倍""播种/对战/排位"等文案）被 OCR 误判
 * "已进入农场"→ 后续在大厅乱点浇筑/收获并假成功。本测试用真实大厅文案回归校验。
 */
class GameSceneDetectorTextTest {

    private fun sc(text: String) = GameSceneDetector.classifyFromText(text)

    @Test
    fun `大厅横幅文字不会误判为农场`() {
        val lobby = sc("王者农场 周末双倍 对战 排位 玩法大厅 首胜奖励可用")
        // "王者农场"曾是 FARM_KEYWORDS 误判源，已剔除；此处只需断言"不是农场"即可
        org.junit.Assert.assertNotEquals(GameSceneDetector.Scene.FARM.name, lobby.name)
    }

    @Test
    fun `大厅含浇水字样不误判为农场`() {
        // 防止"浇水/成熟/作物/土地"等宽泛词被当成农场入口
        val lobby = sc("浇水 成熟 作物 土地 灌溉 播种")
        org.junit.Assert.assertNotEquals(GameSceneDetector.Scene.FARM.name, lobby.name)
    }

    @Test
    fun `农场页面强特征词判定为农场`() {
        val farm = sc("一键浇水 一键收获 农场等级 可开垦 我的农场 农场小摊 浇水壶")
        assertEquals(GameSceneDetector.Scene.FARM.name, farm.name)
    }

    @Test
    fun `纯大厅排位文案判定为大厅`() {
        val hall = sc("排位赛 巅峰赛 备战 战队 商城 铭文")
        assertEquals(GameSceneDetector.Scene.GAME_HALL.name, hall.name)
    }
}
