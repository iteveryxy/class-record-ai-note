package cn.classnotes;

import java.nio.file.Path;
import static cn.classnotes.Models.*;

public interface AsrProvider {
    AsrResult transcribe(Job job, Path audio) throws Exception;
}
