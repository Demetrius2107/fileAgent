package com.demetrius.fileagent.api.enums;

/**
 * 知识内容单元类型；与 CHILD/PARENT 检索组织方式相互独立。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
public enum ContentType {

    /** 普通正文。 */
    TEXT,

    /** 标题或章节标题。 */
    TITLE,

    /** 保留结构语义的表格内容。 */
    TABLE,

    /** 图片或图表内容。 */
    IMAGE
}
