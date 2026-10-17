package cn.classnotes;

import static cn.classnotes.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.demo",havingValue="true")
public class DemoProvider implements AsrProvider, LlmProvider {
    private final Store store; private final ObjectMapper json;
    public DemoProvider(Store store,ObjectMapper json) { this.store=store;this.json=json; }
    public AsrResult transcribe(Job job,Path audio) {
        List<Segment> segments=List.of(
                new Segment(0,12000,"今天讨论导数的定义。导数是函数在一点处的瞬时变化率，由差商极限得到。","demo"),
                new Segment(12000,24000,"我在这里写一下。函数在某点可导，一定在该点连续；连续不一定可导。比如绝对值函数在零点的左右导数不相同。","demo"),
                new Segment(24000,36000,"做题时，先检查定义域，再计算左右导数，判断极限是否存在。分段函数的连接点需要分别讨论。","demo"));
        String text=String.join("\n",segments.stream().map(Segment::text).toList());
        var raw=json.valueToTree(Map.of("demo",true,"message","演示文本，未对上传音频进行识别"));
        store.artifact(job,"ASR_RESPONSE",raw.toString());
        return new AsrResult("demo-fixture",36000,text,segments,raw);
    }
    public String complete(Job job,String system,String content) {
        if(job.stage()==Stage.REFINE) {
            String reply="{\"reply\":\"当前为演示模式，未调用真实模型，也未修改笔记。正式模式会使用你配置的模型执行修改要求。\",\"edits\":[]}";
            store.artifact(job,"LLM_RESPONSE",reply);
            return reply;
        }
        String markdown="""
                > 演示笔记：以下为固定示例，未分析实际上传音频。正式模式使用本地 SenseVoice 与所配置的模型 API。

                ## 本节概览
                从差商极限理解导数，掌握可导与连续的关系，学会判断分段函数在连接点的可导性。

                ## 核心概念
                **导数**表示函数在一点处的瞬时变化率，也对应曲线在该点切线的斜率。[00:00:00]

                ## 公式与定理
                导数的定义：

                $$
                f'(x_0)=\\lim_{h\\to 0}\\frac{f(x_0+h)-f(x_0)}{h}
                $$

                **可导一定连续，连续不一定可导。** 反例：$f(x)=|x|$ 在 $x=0$ 连续，但左右导数不相等。[00:00:12]

                ## 解题思路
                1. 检查函数定义域以及连接点的连续性。
                2. 分别计算左导数与右导数。
                3. 两侧极限存在且相等时，函数在该点可导。[00:00:24]

                ## 易错点
                - 不能由“连续”直接推出“可导”。
                - 判断分段函数时，不能只套用某一段的求导公式。

                ## 待核对
                实际课堂的公式、例题及音频时间需要在正式模式下核对。

                ## 复习自测
                为什么绝对值函数在零点连续，却不可导？
                """;
        store.artifact(job,"LLM_RESPONSE",json.valueToTree(Map.of("demo",true,"content",markdown)).toString());
        return markdown;
    }
}
