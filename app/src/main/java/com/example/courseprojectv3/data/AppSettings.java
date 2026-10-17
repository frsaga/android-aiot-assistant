package com.example.courseprojectv3.data;

/**
 * 应用配置模型。
 *
 * 答辩说明：
 * 设置页修改的模型名称、API KEY、暗色模式和 Claw 开关都会映射到这个类，
 * 再由 AppStorage 保存到 SharedPreferences。
 */
public class AppSettings {
    public String modelName = "qwen-plus";
    public String apiKey = "";
    public boolean darkMode = false;
    public boolean clawMode = false;
}
