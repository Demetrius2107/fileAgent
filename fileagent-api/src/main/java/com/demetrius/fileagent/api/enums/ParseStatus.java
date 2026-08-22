package com.demetrius.fileagent.api.enums;

public enum ParseStatus {
    /** 待解析 */
    PENDING,
    /** 解析中 */
    PARSING,
    /** 解析完成 */
    SUCCESS,
    /** 解析失败 */
    FAILED
}
