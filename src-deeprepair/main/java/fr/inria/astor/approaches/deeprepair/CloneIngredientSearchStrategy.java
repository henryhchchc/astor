package fr.inria.astor.approaches.deeprepair;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.martiansoftware.jsap.JSAPException;

import fr.inria.astor.approaches.jgenprog.operators.ReplaceOp;
import fr.inria.astor.core.entities.Ingredient;
import fr.inria.astor.core.entities.ModificationPoint;
import fr.inria.astor.core.ingredientbased.IngredientBasedEvolutionaryRepairApproachImpl;
import fr.inria.astor.core.manipulation.MutationSupporter;
import fr.inria.astor.core.manipulation.filters.SingleStatementFixSpaceProcessor;
import fr.inria.astor.core.setup.ConfigurationProperties;
import fr.inria.astor.core.setup.RandomManager;
import fr.inria.astor.core.solutionsearch.spaces.ingredients.CodeParserLauncher;
import fr.inria.astor.core.solutionsearch.spaces.ingredients.IngredientPool;
import fr.inria.astor.core.solutionsearch.spaces.ingredients.ingredientSearch.RandomSelectionTransformedIngredientStrategy;
import fr.inria.astor.core.solutionsearch.spaces.ingredients.transformations.ClusterIngredientTransformation;
import fr.inria.astor.core.solutionsearch.spaces.ingredients.transformations.IngredientTransformationStrategy;
import fr.inria.astor.core.solutionsearch.spaces.operators.AstorOperator;
import fr.inria.astor.core.stats.Stats;
import fr.inria.astor.util.StringUtil;
import spoon.reflect.code.CtCodeElement;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtAnonymousExecutable;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtNamedElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * A strategy to pick an ingredient from the fix space using code fragments'
 * similarities.
 * 
 * @author Martin White
 *
 */
public class CloneIngredientSearchStrategy<T extends CtNamedElement> extends RandomSelectionTransformedIngredientStrategy {

    private final Class cls;
    private TypeFilter typeFilter;
    // Each item is a string of comma-separated values representing distances.
    private List<String> distances;
    // String is row from src2txt. Integer is index into this.distances.
    private Map<String, Integer> key2row;
    private Map<Integer, String> row2key;
    // Elements in scope.
    private Map<String, T> key2element = new HashMap<>();
    // Cache of elements' similarity lists.
    private Map<T, List<T>> element2simlist = new HashMap<>();

    private IngredientTransformationStrategy ingTransformationStrategy = null;

    public CloneIngredientSearchStrategy(IngredientPool space) throws Exception {
        super(space);
        cls = Class.forName(ConfigurationProperties.properties.getProperty("clonegranularity"));
        setfilter();
        readinput();

        this.ingTransformationStrategy = IngredientBasedEvolutionaryRepairApproachImpl.retrieveIngredientTransformationStrategy();

        if (this.ingTransformationStrategy == null) {
            this.ingTransformationStrategy = new ClusterIngredientTransformation();
        }
    }

    public CloneIngredientSearchStrategy(IngredientPool space, Class _cloneClass,
            IngredientTransformationStrategy ingredientTransformationStrategy) throws Exception {
        super(space);
        cls = _cloneClass;
        setfilter();
        readinput();
        this.ingTransformationStrategy = ingredientTransformationStrategy;
    }

    private void setfilter() {
        if (cls.equals(CtType.class)) {
            typeFilter = new TypeFilter<CtType>(CtType.class) {
                @Override
                public boolean matches(CtType element) {
                    // Definition of "top level" CtType.
                    return element.getParent(CtType.class) == null && !element.isImplicit();
                }
            };
        } else if (cls.equals(CtExecutable.class)) {
            typeFilter = new TypeFilter<CtExecutable>(CtExecutable.class) {
                @Override
                public boolean matches(CtExecutable element) {
                    // Definition of "top level" CtExecutable.
                    return element.getParent(CtExecutable.class) == null && !element.isImplicit()
                            && !(element instanceof CtAnonymousExecutable);
                }
            };
        } else {
            log.error("Invalid clonegranularity");
            throw new IllegalArgumentException();
        }
        log.debug("clonegranularity: " + cls.getName());
    }

    private void readinput() throws IOException {
        // Read distance vectors stored in distances.csv file (e.g.,
        // executables.distances.csv).
        // Distance vectors will be parsed on demand.
        distances = read(Input.DISTANCES.getpath());

        // Read keys stored in key file (e.g., executables.key).
        List<String> keys = read(Input.KEYS.getpath());

        // Read map stored in map file (e.g., executables.map).
        // The map file contains one record of comma-separated values.
        List<String> values = Arrays.asList(read(Input.MAP.getpath()).get(0).split(","));

        // We need to subtract 1 because MATLAB is 1-indexed.
        List<Integer> map = new ArrayList<>();
        values.forEach(value -> map.add(Integer.valueOf(value) - 1));

        // We randomly permuted the rows of the {executables,types}.int corpora
        // before encoding them.
        // Therefore, to get the row for a row in the distance matrix, the row
        // number needs to be mapped to the row.
        String key;
        key2row = new HashMap<>();
        row2key = new HashMap<>();
        for (int row = 0; row < map.size(); row++) {
            key = keys.get(map.get(row));
            if (key2row.containsKey(key)) {
                log.error("Duplicate key: " + key);
                throw new RuntimeException();
            }
            key2row.put(key, row);
            row2key.put(row, key);
        }
        log.debug("Number of keys loaded: " + key2row.keySet().size());
    }

    private List<String> read(Path path) throws IOException {
        try (Stream<String> stream = Files.lines(path)) {
            return stream.map(String::trim).collect(Collectors.toList());
        }
    }

    @Override
    public Ingredient getFixIngredient(ModificationPoint modificationPoint, AstorOperator op) {
        // TODO Move to constructor when Spoon model building happens before
        // ingredient strategy is initialized.
        if (key2element.isEmpty())
            key2element = queryelements().orElseThrow(RuntimeException::new);

        T suspicious = (T) modificationPoint.getCodeElement().getParent(typeFilter);

        if (suspicious == null) {
            // TODO Count number of times modification point does not map to
            // "top level" T.
            log.info("Modification point does not map to \"top level\" " + cls.getName() + ": " + modificationPoint);
            return null;
        }

        String key = getkey(suspicious);
        log.debug("Suspicious element: " + key);

        if (!key2element.containsKey(key)) {
            log.error("Suspicious element is not in scope: " + key);
            throw new RuntimeException();
        }

        // element2simlist is a cache of element-specific similarity lists.
        if (!element2simlist.containsKey(suspicious))
            computesimlist(suspicious);

        Queue<CtCodeElement> fixspace = getfixspace(modificationPoint, op, suspicious);
        int searchSpaceSize = fixspace.size();
        log.debug("Fix space is empty? " + fixspace.isEmpty());
        if (fixspace.isEmpty())
            return null;

        boolean continueSearching = true;

        int variant_id = modificationPoint.getProgramVariant().getId();
        Stats.currentStat.getIngredientsStats().initializeIngCounter(variant_id);
        // counts the number of transformations done for an ingredients.
        int numberOfIngredientTransformationsDone = 0;
        while (continueSearching) {
            CtElement ingredient = getingredient(fixspace);
            log.debug("Location to insert " + modificationPoint);

            if (ingredient == null)
                return null;

            log.debug("-->Ingredient selected: " + StringUtil.trunc(ingredient.getShortRepresentation()));

            boolean alreadyApplied = alreadySelected(modificationPoint, ingredient, op);

            if (alreadyApplied) {
                log.debug("Ingredient Already applied " + modificationPoint + " " + StringUtil.trunc(ingredient));
                continue;
            }

            boolean sameCode = ingredient.equals(modificationPoint.getCodeElement());

            if (sameCode) {
                log.debug("Ingredient same that the mod point");
                continue;
            }

            Stats.currentStat.getIngredientsStats().incrementIngCounter(variant_id);

            Ingredient ingredientToModify = new Ingredient(ingredient);

            List<Ingredient> ingredientsTransformed = ingTransformationStrategy.transform(modificationPoint,
                    ingredientToModify);
            if (ingredientsTransformed == null) {
                log.debug("Ingredients transf list is null");
            }

            if (ingredientsTransformed.size() > 0)
                return ingredientsTransformed.get(0);
        }

        log.debug("--- no mutation left to apply in element " + modificationPoint.getCodeElement()
                + ", search space size: " + searchSpaceSize);
        return null;
    }

    /**
     * Return a combination of variables.
     * 
     * @param allCombinations
     * @return
     */
    public Map<String, CtVariable> obtainCombination(List<Map<String, CtVariable>> allCombinations) {

        if (ConfigurationProperties.getPropertyBool("randomcombination")) {
            int value = RandomManager.nextInt(allCombinations.size());
            return allCombinations.get(value);
        } else {
            return allCombinations.get(0);
        }
    }

    private String getkey(T element) {
        String key;
        if (element instanceof CtExecutable) {
            key = element.getParent(CtType.class).getQualifiedName() + "#" + ((CtExecutable<?>) element).getSignature();
        } else if (element instanceof CtType) {
            key = ((CtType<?>) element).getQualifiedName();
        } else {
            log.error("Invalid clonegranularity");
            throw new IllegalArgumentException();
        }
        return key;
    }

    public Optional<Map<String, T>> queryelements() {
        // Use ingredient space to get locations.
        List<CtElement> locations = getIngredientSpace().getLocations();
        if (locations == null || locations.size() == 0) {
            log.error("There are no locations to analyze.");
            throw new RuntimeException();
        }
        log.debug("Number of locations: " + locations.size() + ", Scope "
                + getIngredientSpace().getClass().getSimpleName());
        // locations.forEach(e -> log.debug("--> ing location:
        // "+e.getShortRepresentation()));

        // Use locations to get T elements.
        int i = 0;
        Map elements;
        if (cls.equals(CtType.class)) {
            elements = locations.stream().flatMap(l -> l.getElements(new TypeFilter<CtType>(CtType.class) {
                @Override
                public boolean matches(CtType element) {
                    // Definition of "top level" CtType.
                    return element.getParent(CtType.class) == null && !element.isImplicit();
                }
            }).stream()).distinct().collect(Collectors.toMap(e -> getkey((T) e), e -> e));
        } else if (cls.equals(CtExecutable.class)) {

            elements = new HashMap<>();
            for (CtElement location : locations) {
                List<CtExecutable> elementsfromLocation = location
                        .getElements(new TypeFilter<CtExecutable>(CtExecutable.class) {
                            @Override
                            public boolean matches(CtExecutable element) {
                                // Definition of "top level" CtExecutable.
                                boolean t = element.getParent(CtExecutable.class) == null && !element.isImplicit()
                                        && !(element instanceof CtAnonymousExecutable);
                                return t;
                            }
                        });
                for (CtExecutable ctExecutable : elementsfromLocation) {

                    CtExecutable previousValue = (CtExecutable) elements.put(getkey((T) ctExecutable), ctExecutable);
                    if (previousValue != null && !previousValue.equals(ctExecutable)) {
                        throw new RuntimeException();
                    }
                }

            }

        } else {
            log.error("Invalid clonegranularity");
            throw new IllegalArgumentException();
        }
        int i2 = 0;
        for (Object element : elements.keySet()) {
            System.out.println(i2++ + "-->2" + element);
        }
        log.debug("Number of \"top level\" elements: " + elements.size());
        // log.debug("\"Top level\" elements: " +
        // elements.keySet().stream().collect(Collectors.joining(",")));
        Set<String> orphans = new HashSet<>(elements.keySet());
        orphans.removeAll(key2row.keySet());

        if (!orphans.isEmpty()) {
            log.error("Number of \"top level\" elements that do not have a src2txt key: " + orphans.size());
            log.error(orphans.stream().collect(Collectors.joining(",")));
            throw new RuntimeException();
        }

        return Optional.of(elements);
    }

    private void computesimlist(T element) {
        String key = getkey(element);
        int row = key2row.get(key); // Get row of distance matrix.
        String[] values = distances.get(row).split(",");

        // The distance matrix should be square (for now).
        if (key2row.size() != values.length) {
            // row is 0-indexed so we add 1.
            log.error(String.format("Distance vector on line %d has wrong dimension.", row + 1));
            throw new RuntimeException();
        }

        List<Distance> distances = new ArrayList<>();
        for (int i = 0; i < values.length; i++)
            distances.add(new Distance(i, Double.parseDouble(values[i])));

        List<T> simlist = distances.stream()
                // Sort Distance instances in ascending order.
                .sorted(Distance::compareTo)
                // Map sorted values to row numbers.
                .map(Distance::getRow)
                // Map row numbers to keys.
                .map(row2key::get)
                // Map keys to elements.
                .map(key2element::get)
                // Some code fragments are out of scope.
                .filter(Objects::nonNull).collect(Collectors.toList());

        // Store the sorted list of elements that will be used to process
        // ingredients in a specific order.
        element2simlist.put(element, simlist);
    }

    public Queue<CtCodeElement> getfixspace(ModificationPoint mp, AstorOperator op, T suspicious) {
        // Statements (i.e., ingredients) extracted from elements.
        List<CtStatement> statements = null;

        // The modification point's type in case we selected the ReplaceOp.
        String type = mp.getCodeElement().getClass().getSimpleName();

        // FIFO queue where ingredients from similar elements are queued first.
        Queue<CtCodeElement> fixspace = new LinkedList<>();

        // Get the list of elements sorted by similarity.
        List<T> simlist = element2simlist.get(suspicious);

        log.debug("For " + suspicious.getSimpleName() + " simlist: " + simlist.size());
        for (T element : simlist) {
            try {
                CodeParserLauncher<?, CtStatement> ipro = new CodeParserLauncher<>(
                        new SingleStatementFixSpaceProcessor());
                statements = ipro.createFixSpace(element);
                // log.debug(element.getSimpleName() + " from simlist,
                // statements: (" + statements.size() + ")");
            } catch (JSAPException e) {
                log.error(e);
            }

            // If the element has a body but it doesn't have ingredients then
            // skip it.
            if (statements == null)
                continue;

            // If we intend to replace the suspicious statement then we can only
            // select statements of the same type.
            statements.removeIf(s -> op instanceof ReplaceOp && !s.getClass().getSimpleName().equals(type));

            // Blocks of statements are added to the queue according to how
            // similar the parent element is to the suspicious element.
            // We can use a queue because the operator is fixed in the
            // modification instance.
            fixspace.addAll(statements);
        }

        return fixspace;
    }

    private CtCodeElement getingredient(Queue<CtCodeElement> fixspace) {
        CtCodeElement element = fixspace.poll();
        if (element == null) // ??? Can .clone receive null?
            return null;
        return MutationSupporter.clone(element); // ??? Is it necessary to
                                                    // clone?
    }

    public Class getCls() {
        return cls;
    }
}

enum Input {
    DISTANCES(".distances.csv"), KEYS(".key"), MAP(".map");

    private final String learningdir;
    private final String granularity;
    private final String extension;

    Input(String extension) {
        this.learningdir = ConfigurationProperties.properties.getProperty("learningdir");
        String granularity = ConfigurationProperties.properties.getProperty("clonegranularity");
        // We pass from a class name to a granularity identifier:
        this.granularity = granularity.split("\\.Ct")[1].toLowerCase() + "s";
        this.extension = extension;
    }

    public Path getpath() {
        return Paths.get(this.learningdir + File.separator + this.granularity + this.extension);
    }
}

class Distance implements Comparable<Distance> {
    private int row;
    private Double value; // TODO double

    public Distance(int row, Double value) {
        this.row = row;
        this.value = value;
    }

    public int getRow() {
        return row;
    }

    public Double getValue() {
        return value;
    }

    @Override
    public int compareTo(Distance distance) {
        return this.value.compareTo(distance.getValue());
    }
}
