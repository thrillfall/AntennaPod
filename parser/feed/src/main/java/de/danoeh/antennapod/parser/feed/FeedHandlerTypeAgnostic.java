package de.danoeh.antennapod.parser.feed;

import org.json.JSONException;
import org.xml.sax.SAXException;

import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.parser.feed.parser.JsonFeedParserBuilder;
import de.danoeh.antennapod.parser.feed.parser.XmlFeedParser;
import de.danoeh.antennapod.parser.feed.type.TypeGetterRegistry;
import de.danoeh.antennapod.parser.feed.type.TypeResolver;

public class FeedHandlerTypeAgnostic {
    public FeedHandlerResult parseFeed(Feed feed) throws SAXException, IOException,
            ParserConfigurationException, UnsupportedFeedtypeException, JSONException {
        TypeResolver typeResolver = new TypeResolver(TypeGetterRegistry.getTypeGetters());
        TypeResolver.Type type = typeResolver.getType(feed);
        if (
                type.equals(TypeResolver.Type.ATOM)
                        || type.equals(TypeResolver.Type.RSS20)
                        || type.equals(TypeResolver.Type.RSS091)
        ) {
            return (new XmlFeedParser()).createFeedHandlerResult(feed, type);
        }

        if (type.equals(TypeResolver.Type.JSON)) {
            return (new JsonFeedParserBuilder().createJsonFeedParser()).createFeedHandlerResult(feed, type);
        }

        throw new UnsupportedFeedtypeException(String.valueOf(TypeResolver.Type.INVALID));
    }


}
