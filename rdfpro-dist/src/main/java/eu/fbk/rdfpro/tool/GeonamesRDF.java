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
package eu.fbk.rdfpro.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.rio.ParserConfig;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFParserFactory;
import org.openrdf.rio.Rio;
import org.openrdf.rio.helpers.BasicParserSettings;
import org.openrdf.rio.helpers.RDFHandlerWrapper;
import org.openrdf.rio.helpers.RDFParserBase;
import org.openrdf.rio.helpers.XMLParserSettings;

public class GeonamesRDF implements RDFParserFactory {

    public static final RDFFormat FORMAT = new RDFFormat("Geonames RDF",
            "application/x-geonames-rdf", null, "geonames", true, true);

    static {
        RDFFormat.register(FORMAT);
    }

    public static void init() {
        // calling this method will cause the static initializer to run once
    }

    @Override
    public RDFFormat getRDFFormat() {
        return FORMAT;
    }

    @Override
    public RDFParser getParser() {
        return new Parser();
    }

    public static class Parser extends RDFParserBase {

        public Parser() {
            super();
        }

        public Parser(final ValueFactory factory) {
            super(factory);
        }

        @Override
        public RDFFormat getRDFFormat() {
            return FORMAT;
        }

        @Override
        public void parse(final InputStream in, final String baseURI) throws IOException,
                RDFParseException, RDFHandlerException {

            final RDFHandler handler = new RDFHandlerWrapper(getRDFHandler()) {

                @Override
                public void startRDF() throws RDFHandlerException {
                }

                @Override
                public void endRDF() throws RDFHandlerException {
                }

            };

            getRDFHandler().startRDF();

            final ZipInputStream stream = new ZipInputStream(in);
            try {
                while (stream.getNextEntry() != null) {
                    final BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
                            Charset.forName("UTF-8")));
                    while (reader.readLine() != null) { // read and drop URI
                        final String entry = reader.readLine();

                        final RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
                        parser.setRDFHandler(handler);
                        parser.setValueFactory(ValueFactoryImpl.getInstance());

                        final ParserConfig config = parser.getParserConfig();
                        config.set(BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES, false);
                        config.set(BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES, false);
                        config.set(BasicParserSettings.VERIFY_DATATYPE_VALUES, false);
                        config.set(BasicParserSettings.VERIFY_LANGUAGE_TAGS, false);
                        config.set(BasicParserSettings.VERIFY_RELATIVE_URIS, false);
                        config.set(BasicParserSettings.NORMALIZE_DATATYPE_VALUES, true);
                        config.set(BasicParserSettings.NORMALIZE_LANGUAGE_TAGS, true);
                        config.set(BasicParserSettings.PRESERVE_BNODE_IDS, true);
                        config.set(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID, false);
                        config.set(XMLParserSettings.FAIL_ON_INVALID_NCNAME, false);
                        config.set(XMLParserSettings.FAIL_ON_INVALID_QNAME, false);
                        config.set(XMLParserSettings.FAIL_ON_MISMATCHED_TAGS, false);
                        config.set(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES, false);
                        config.set(XMLParserSettings.FAIL_ON_SAX_NON_FATAL_ERRORS, false);

                        parser.parse(new StringReader(entry), baseURI);
                    }
                }
            } catch (final ZipException ex) {
                if (!ex.getMessage().contains("invalid entry size")) {
                    throw ex;
                }

            } finally {
                stream.close();
            }

            getRDFHandler().endRDF();
        }

        @Override
        public void parse(final Reader reader, final String baseURI) throws IOException,
                RDFParseException, RDFHandlerException {
            throw new UnsupportedOperationException("Binary data expected");
        }

    }

}
