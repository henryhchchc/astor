
package fr.inria.astor.core.faultlocalization.predefined;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import fr.inria.astor.core.faultlocalization.FaultLocalizationResult;
import fr.inria.astor.core.faultlocalization.FaultLocalizationStrategy;
import fr.inria.astor.core.faultlocalization.entity.SuspiciousCode;
import fr.inria.astor.core.setup.ProjectRepairFacade;

/**
 * PreDefinedFaultLocalization
 */
public class PreDefinedFaultLocalization implements FaultLocalizationStrategy {

    private final String testName;
    private final String targetClassName;
    private final int startingLineNum;
    private final int endLineNum;

    private PreDefinedFaultLocalization(
            String testName,
            String targetClassName,
            int startingLineNum,
            int endLineNum) {
        this.testName = testName;
        this.targetClassName = targetClassName;
        this.startingLineNum = startingLineNum;
        this.endLineNum = endLineNum;
    }

    @Override
    public FaultLocalizationResult searchSuspicious(ProjectRepairFacade projectToRepair, List<String> testToRun)
            throws Exception {
        List<SuspiciousCode> candidates = new ArrayList<>();
        for (int i = startingLineNum; i <= endLineNum; i++) {
            SuspiciousCode code = new SuspiciousCode(targetClassName, "setupMocks", i, 1.0, Collections.emptyMap());
            candidates.add(code);
        }
        FaultLocalizationResult result = new FaultLocalizationResult(candidates);
        return result;
    }

    @Override
    public List<String> findTestCasesToExecute(ProjectRepairFacade projectFacade) {
        return Collections.singletonList(testName);
    }

    public static PreDefinedFaultLocalization parse(String infoTxt) {
        List<String> lines = infoTxt.lines().map(it -> it.trim()).collect(Collectors.toList());
        return new PreDefinedFaultLocalization(
                lines.get(3),
                lines.get(0),
                Integer.parseInt(lines.get(1)),
                Integer.parseInt(lines.get(2)));
    }

    public static PreDefinedFaultLocalization load(String path) throws IOException {
        String content = Files.readString(new File(path).toPath());
        return parse(content);
    }

}
