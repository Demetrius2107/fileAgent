package com.demetrius.fileagent.document;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 仓储层切片测试（{@code @DataJpaTest}）的引导配置。
 * <p>
 * 切片测试从测试类所在包向上查找 {@code @SpringBootConfiguration}；
 * 放在模块根包使实体（domain）与仓储（infrastructure）都落在默认扫描范围内，
 * 无需再使用 EntityScan（Boot 4 已随自动配置模块拆分移除）。
 *
 * @author Demetrius
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class RagFileJpaTestBootstrap {
}
