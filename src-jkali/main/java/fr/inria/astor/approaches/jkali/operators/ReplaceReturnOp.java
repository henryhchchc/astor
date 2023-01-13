package fr.inria.astor.approaches.jkali.operators;

import java.util.ArrayList;
import java.util.List;

import fr.inria.astor.approaches.jgenprog.operators.ReplaceOp;
import fr.inria.astor.approaches.jgenprog.operators.StatementLevelOperator;
import fr.inria.astor.core.entities.ModificationPoint;
import fr.inria.astor.core.entities.OperatorInstance;
import fr.inria.astor.core.entities.ProgramVariant;
import fr.inria.astor.core.entities.StatementOperatorInstance;
import fr.inria.astor.core.solutionsearch.spaces.operators.AutonomousOperator;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtElement;

/**
 * 
 * @author Matias Martinez
 *
 */
public class ReplaceReturnOp extends AutonomousOperator implements StatementLevelOperator {

    ReplaceOp replaceOp = new ReplaceOp();

    @Override
    public List<OperatorInstance> createOperatorInstances(ModificationPoint modificationPoint) {
        List<OperatorInstance> instances = new ArrayList<>();

        CtElement createReturn = KaliCodeFactory.createReturn(modificationPoint.getCodeElement());

        if (createReturn != null) {
            OperatorInstance opInsertReturn = new StatementOperatorInstance(modificationPoint, this,
                    modificationPoint.getCodeElement(), createReturn);
            instances.add(opInsertReturn);
        }

        return instances;
    }

    @Override
    public boolean applyChangesInModel(OperatorInstance operation, ProgramVariant p) {

        return replaceOp.applyChangesInModel(operation, p);

    }

    @Override
    public boolean undoChangesInModel(OperatorInstance opInstance, ProgramVariant p) {
        return replaceOp.undoChangesInModel(opInstance, p);
    }

    @Override
    public boolean updateProgramVariant(OperatorInstance opInstance, ProgramVariant p) {
        return replaceOp.updateProgramVariant(opInstance, p);
    }

    @Override
    public boolean canBeAppliedToPoint(ModificationPoint point) {

        return point.getCodeElement() instanceof CtStatement;
    }

}