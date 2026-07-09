package com.haiwancodex.www.common;

// 错误类型枚举，写在类内部
public enum ToolErrType {
    PATH_INVALID("PATH_INVALID", "路径非法逃逸/格式错误", "请使用工作区相对路径，禁止../、绝对路径、上级目录跳转"),
    CONTEXT_MISS("CONTEXT_MISS", "ToolContext缺失工作区信息", "会话丢失workspaceId，重新加载项目工作区"),
    FILE_NOT_EXIST("FILE_NOT_EXIST", "目标文件不存在", "核对路径拼写，不存在则先调用 createDirectory 创建父目录"),
    NOT_DIRECTORY("NOT_DIRECTORY", "传入路径不是文件夹", "listDirectory/treeDirectory仅支持目录，读取文件用 readFile"),
    DIR_NOT_EMPTY("DIR_NOT_EMPTY", "目录存在文件无法直接删除", "如需清空目录使用 deleteForce 工具"),
    FILE_TOO_LARGE("FILE_TOO_LARGE", "文件体积过大禁止全量读取", "使用 searchInFiles 检索关键字，不要一次性读取完整大文件"),
    IO_PERMISSION_ERROR("IO_PERMISSION_ERROR", "文件读写权限不足/被占用", "检查文件读写权限，关闭占用该文件的进程"),
    IO_UNKNOWN("IO_UNKNOWN", "未知文件IO异常", "更换操作路径或简化操作重试"),
    PARAM_EMPTY("PARAM_EMPTY", "必填参数为空", "补全工具必填路径/内容参数后重新调用"),
    SERIALIZE_ERROR("SERIALIZE_ERROR", "JSON序列化失败", "检查传入代码是否包含非法转义字符，简化内容重试");

    public final String code;
    public final String desc;
    public final String fixHint;

    ToolErrType(String code, String desc, String fixHint) {
        this.code = code;
        this.desc = desc;
        this.fixHint = fixHint;
    }
}