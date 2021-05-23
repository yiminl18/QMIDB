package QMIDB;

import java.util.List;

public class QueryProcessing {
    public QueryProcessing(List<Attribute> Attributes, List<PredicateUnit> preds) {
        //initialization
        RelationshipGraph RG = new RelationshipGraph(Attributes, preds);

    }
}
