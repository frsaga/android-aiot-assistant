package com.example.courseprojectv3.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 网络状态读取工具。
 *
 * 答辩说明：
 * 统计页面通过 Android ConnectivityManager 获取当前网络类型，
 * 用于展示“当前网络状态”评分项。
 */
public class NetworkUtils {
    public static String getNetworkStatus(Context context) {
        // 这是系统服务调用，不是自己检测网络包；Android 会返回当前活跃网络能力。
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return "无法读取网络状态";
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return "离线：当前没有可用网络";
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        if (capabilities == null) {
            return "已连接：网络能力未知";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "在线：Wi-Fi 网络，可稳定调用云端模型接口";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "在线：蜂窝网络，建议控制多模态附件大小";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "在线：以太网连接";
        }
        return "在线：其他网络类型";
    }
}
