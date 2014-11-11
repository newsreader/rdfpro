/*
 * RDFpro - An extensible tool for building stream-oriented RDF processing libraries.
 * 
 * Written in 2014 by Francesco Corcoglioniti <francesco.corcoglioniti@gmail.com> with support by
 * Marco Rospocher, Marco Amadori and Michele Mostarda.
 * 
 * To the extent possible under law, the author has dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is
 * distributed without any warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package eu.fbk.rdfpro;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.vocabulary.OWL;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.RDFS;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TBoxProcessor extends RDFProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TBoxProcessor.class);

    public TBoxProcessor() {
    }

    @Override
    public int getExtraPasses() {
        return 0;
    }

    @Override
    public RDFHandler getHandler(final RDFHandler handler) {
        return new Handler(Handlers.decouple(Util.checkNotNull(handler)));
    }

    private static final class Handler implements RDFHandler, Closeable {

        private static final int NUM_LOCKS = 128;

        private final RDFHandler handler;

        private final Map<URI, Term> terms;

        private final Object[] locks;

        Handler(final RDFHandler handler) {
            this.handler = handler;
            this.terms = new ConcurrentHashMap<URI, Term>();
            this.locks = new Object[NUM_LOCKS];
            for (int i = 0; i < this.locks.length; ++i) {
                this.locks[i] = new Object();
            }
        }

        @Override
        public void startRDF() throws RDFHandlerException {
            this.handler.startRDF();
            this.terms.clear();
            for (final URI type : Util.TBOX_CLASSES) {
                this.terms.put(type, new Term(true, false, true, false));
            }
            for (final URI property : Util.TBOX_PROPERTIES) {
                this.terms.put(property, new Term(true, true, true, false));
            }
        }

        @Override
        public void handleComment(final String comment) throws RDFHandlerException {
            // discarded
        }

        @Override
        public void handleNamespace(final String prefix, final String uri)
                throws RDFHandlerException {
            this.handler.handleNamespace(prefix, uri);
        }

        @Override
        public void handleStatement(final Statement statement) throws RDFHandlerException {

            final Resource s = statement.getSubject();
            final URI p = statement.getPredicate();
            final Value o = statement.getObject();

            boolean emit = false;

            if (!p.equals(RDF.TYPE)) {
                synchronized (getLock(p)) {
                    Term term = this.terms.get(p);
                    if (term == null) {
                        term = new Term(false, true, false, true);
                        this.terms.put(p, term);
                    } else if (term.isLanguage) {
                        term.isUsed = true;
                        emit = true;
                    }
                }
            } else if (o instanceof URI) {
                synchronized (getLock(o)) {
                    Term term = this.terms.get(o);
                    if (term == null) {
                        term = new Term(false, false, false, true);
                        this.terms.put((URI) o, term);
                    } else if (term.isLanguage) {
                        term.isUsed = true;
                        emit = true;
                    }
                }
                if (s instanceof URI) {
                    final boolean isType = o.equals(RDFS.CLASS) || o.equals(OWL.CLASS);
                    final boolean isProperty = o.equals(RDF.PROPERTY)
                            || o.equals(OWL.DATATYPEPROPERTY) || o.equals(OWL.OBJECTPROPERTY)
                            || o.equals(OWL.ANNOTATIONPROPERTY);
                    if (isType || isProperty) {
                        synchronized (getLock(s)) {
                            Term sterm = this.terms.get(s);
                            if (sterm == null) {
                                sterm = new Term(false, isProperty, true, true);
                                this.terms.put((URI) s, sterm);
                            } else {
                                sterm.isDefined = true;
                            }
                        }
                    }
                }
            }

            if (emit) {
                this.handler.handleStatement(statement);
            }
        }

        @Override
        public void endRDF() throws RDFHandlerException {

            if (LOGGER.isInfoEnabled()) {

                int numTypes = 0;
                int numTypesDefined = 0;

                int numProperties = 0;
                int numPropertiesDefined = 0;

                final List<String> languageTypes = new ArrayList<String>();
                final List<String> languageProperties = new ArrayList<String>();
                final Set<String> undefinedVocabularies = new HashSet<String>();

                for (final Map.Entry<URI, Term> entry : this.terms.entrySet()) {
                    final URI uri = entry.getKey();
                    final Term term = entry.getValue();
                    if (term.isLanguage) {
                        if (term.isUsed) {
                            final String s = Values.formatValue(uri);
                            if (term.isProperty) {
                                languageProperties.add(s);
                            } else {
                                languageTypes.add(s);
                            }
                        }
                    } else {
                        if (term.isProperty) {
                            ++numProperties;
                            numPropertiesDefined += term.isDefined ? 1 : 0;
                        } else {
                            ++numTypes;
                            numTypesDefined += term.isDefined ? 1 : 0;
                        }
                        if (!term.isDefined) {
                            undefinedVocabularies.add(uri.getNamespace());
                        }
                    }
                }

                Collections.sort(languageTypes);
                Collections.sort(languageProperties);

                if (numTypes > 0) {
                    LOGGER.info("Found " + numTypes + " classes (" + numTypesDefined + " defined)");
                }
                if (numProperties > 0) {
                    LOGGER.info("Found " + numProperties + " properties (" + numPropertiesDefined
                            + " defined)");
                }
                if (!languageTypes.isEmpty()) {
                    LOGGER.info("Found language classes: " + Util.join(" ", languageTypes));
                }
                if (!languageProperties.isEmpty()) {
                    LOGGER.info("Found language properties: " + Util.join(" ", languageProperties));
                }

                if (!undefinedVocabularies.isEmpty()) {
                    for (final String ns1 : new ArrayList<String>(undefinedVocabularies)) {
                        for (final String ns2 : undefinedVocabularies) {
                            if (ns1 != ns2 && ns1.startsWith(ns2)) {
                                undefinedVocabularies.remove(ns1);
                                break;
                            }
                        }
                    }
                    final StringBuilder builder = new StringBuilder(
                            "Found undefined vocabularies:");

                    final String[] sortedVocabularies = new String[undefinedVocabularies.size()];
                    undefinedVocabularies.toArray(sortedVocabularies);
                    Arrays.sort(sortedVocabularies);

                    for (final String ns : sortedVocabularies) {
                        builder.append("\n- ").append(ns);
                    }
                    LOGGER.info(builder.toString());
                }
            }

            this.terms.clear();
            this.handler.endRDF();
        }

        @Override
        public void close() throws IOException {
            Util.closeQuietly(this.handler);
        }

        private Object getLock(final Value value) {
            final String s = value.stringValue(); // assume URI with >= 3 chars
            final int length = s.length();
            final int index = s.charAt(length - 1) * 37 + s.charAt(length - 2);
            return this.locks[(index & 0x7FFFFFFF) % NUM_LOCKS];
        }

    }

    private static class Term {

        boolean isLanguage;

        boolean isProperty;

        boolean isDefined;

        boolean isUsed;

        Term(final boolean isLanguage, final boolean isProperty, final boolean isDefined,
                final boolean isUsed) {
            this.isLanguage = isLanguage;
            this.isProperty = isProperty;
            this.isDefined = isDefined;
            this.isUsed = isUsed;
        }

    }

}
