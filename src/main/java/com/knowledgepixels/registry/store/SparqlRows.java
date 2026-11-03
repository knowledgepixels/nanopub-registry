package com.knowledgepixels.registry.store;

import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Maps SPARQL result values to the Java types the pages expect from MongoDB documents.
 */
final class SparqlRows {

    private SparqlRows() {
    }

    /**
     * The Java value of an RDF term: typed literals become {@code Long}, {@code Integer},
     * {@code Double}, {@code Boolean}, {@code java.util.Date} or {@link Binary}; everything
     * else, IRIs included, becomes a {@code String}.
     */
    static Object toJava(Value v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Literal l) {
            IRI dt = l.getDatatype();
            if (XSD.LONG.equals(dt)) {
                return l.longValue();
            }
            if (XSD.INT.equals(dt) || XSD.INTEGER.equals(dt)) {
                return l.intValue();
            }
            if (XSD.DOUBLE.equals(dt) || XSD.DECIMAL.equals(dt) || XSD.FLOAT.equals(dt)) {
                return l.doubleValue();
            }
            if (XSD.BOOLEAN.equals(dt)) {
                return l.booleanValue();
            }
            if (XSD.DATETIME.equals(dt)) {
                return l.calendarValue().toGregorianCalendar().getTime();
            }
            if (XSD.BASE64BINARY.equals(dt)) {
                return new Binary(Base64.getDecoder().decode(l.stringValue()));
            }
            return l.stringValue();
        }
        return v.stringValue();
    }

    /**
     * Builds a document with the given fields in the given order, leaving out absent ones.
     */
    static Document toDocument(Map<String, Object> fields, List<String> order) {
        Document d = new Document();
        for (String f : order) {
            Object v = fields.get(f);
            if (v != null) {
                d.append(f, v);
            }
        }
        return d;
    }

}
