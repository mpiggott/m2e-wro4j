package org.jboss.tools.m2e.wro4j.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.xml.pull.MXParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public class WroModel
{

    private Map<String, Group> groups;

    public WroModel()
    {
        groups = new HashMap<String, Group>();
    }

    public void read( InputStream in )
        throws XmlPullParserException, IOException
    {
        try
        {
            XmlPullParser parser = new MXParser();
            parser.setInput( ReaderFactory.newXmlReader( in ) );
            Group group = null;
            int eventType = parser.getEventType();
            while ( eventType != XmlPullParser.END_DOCUMENT )
            {
                if ( eventType == XmlPullParser.START_TAG )
                {
                    String elementName = parser.getName();
                    if ( "groups".equals( elementName ) )
                    {
                        // do nothing
                    }
                    else if ( "group".equals( elementName ) )
                    {
                        group = new Group();
                        groups.put( getAttribute( parser, "name" ), group );
                    }
                    else if ( "js".equals( elementName ) )
                    {
                        group.addElement( new JavascriptFile( parser.nextText().trim() ) );
                    }
                    else if ( "css".equals( elementName ) )
                    {
                        group.addElement( new CssFile( parser.nextText().trim() ) );
                    }
                    else if ( "group-ref".equals( elementName ) )
                    {
                        group.addElement( new GroupRef( parser.nextText().trim() ) );
                    }
                    else
                    {
                        throw new XmlPullParserException( "Unknown element " + elementName );
                    }
                }
                else if ( eventType == XmlPullParser.END_TAG )
                {
                    if ( "group".equals( parser.getName() ) )
                    {
                        group = null;
                    }
                }
                eventType = parser.next();
            }
        }
        finally
        {
            IOUtil.close( in );
        }
    }

    private static String getAttribute( XmlPullParser parser, String attribute )
    {
        for ( int i = parser.getAttributeCount() - 1; i >= 0; i-- )
        {
            if ( parser.getAttributeName( i ).equals( attribute ) )
            {
                return parser.getAttributeValue( i );
            }
        }
        return null;
    }

    public Group getGroup( String groupId )
    {
        return groups.get( groupId );
    }

    public interface Element
    {
        Collection<String> getJs();

        Collection<String> getCss();
    }

    private static class JavascriptFile
        implements Element
    {
        private final String file;

        private JavascriptFile( String file )
        {
            this.file = file;
        }

        @Override
        public Collection<String> getJs()
        {
            return Collections.singletonList( file );
        }

        @Override
        public Collection<String> getCss()
        {
            return Collections.emptyList();
        }
    }

    private static class CssFile
        implements Element
    {
        private final String file;

        private CssFile( String file )
        {
            this.file = file;
        }

        @Override
        public Collection<String> getJs()
        {
            return Collections.emptyList();
        }

        @Override
        public Collection<String> getCss()
        {
            return Collections.singletonList( file );
        }
    }

    private class GroupRef
        implements Element
    {
        private final String groupRef;

        private GroupRef( String groupRef )
        {
            this.groupRef = groupRef;
        }

        @Override
        public Collection<String> getJs()
        {
            return groups.get( groupRef ).getJs();
        }

        @Override
        public Collection<String> getCss()
        {
            return groups.get( groupRef ).getCss();
        }

    }

    public static class Group
        implements Element
    {

        private List<Element> elements;

        private Set<String> js;

        private Set<String> css;

        private Group()
        {
            elements = new LinkedList<Element>();
        }

        public void addElement( Element element )
        {
            elements.add( element );
        }

        @Override
        public Collection<String> getJs()
        {
            if ( js == null )
            {
                Set<String> js = new LinkedHashSet<String>();
                for ( Element something : elements )
                {
                    js.addAll( something.getJs() );
                }
                this.js = js;
            }
            return js;
        }

        @Override
        public Collection<String> getCss()
        {
            if ( css == null )
            {
                Set<String> css = new LinkedHashSet<String>();
                for ( Element something : elements )
                {
                    css.addAll( something.getCss() );
                }
                this.css = css;
            }
            return css;
        }

    }
}
