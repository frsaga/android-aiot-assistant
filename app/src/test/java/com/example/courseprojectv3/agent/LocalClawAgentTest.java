package com.example.courseprojectv3.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalClawAgentTest {
    private final LocalClawAgent agent = new LocalClawAgent();

    @Test
    public void parsesShortSmsServiceNumber() {
        LocalClawAgent.Command command = agent.parse("sms to 10086");

        assertEquals(LocalClawAgent.ActionType.SEND_SMS, command.type);
        assertEquals("10086", command.phone);
    }

    @Test
    public void parsesChineseSmsRecipientAndBody() {
        LocalClawAgent.Command command = agent.parse("给 13800138000 发短信，内容：实验已经完成");

        assertEquals(LocalClawAgent.ActionType.SEND_SMS, command.type);
        assertEquals("13800138000", command.phone);
        assertEquals("实验已经完成", command.content);
    }

    @Test
    public void parsesCameraSynonyms() {
        assertEquals(LocalClawAgent.ActionType.TAKE_PHOTO, agent.parse("打开相机检查设备").type);
        assertEquals(LocalClawAgent.ActionType.TAKE_PHOTO, agent.parse("take photo now").type);
    }

    @Test
    public void parsesDownloadUrlAndRemovesChinesePunctuation() {
        LocalClawAgent.Command command = agent.parse("请下载 https://example.com/files/demo.pdf，保存到本地");

        assertEquals(LocalClawAgent.ActionType.DOWNLOAD, command.type);
        assertEquals("https://example.com/files/demo.pdf", command.url);
    }

    @Test
    public void parsesNaturalLanguageDownloadTarget() {
        LocalClawAgent.Command command = agent.parse("我要下载 Android 图标");

        assertEquals(LocalClawAgent.ActionType.DOWNLOAD, command.type);
        assertTrue(command.url.isEmpty());
        assertEquals("Android 图标", command.target);
    }

    @Test
    public void ignoresOrdinaryConversation() {
        assertEquals(LocalClawAgent.ActionType.NONE, agent.parse("解释 MQTT QoS").type);
    }
}
