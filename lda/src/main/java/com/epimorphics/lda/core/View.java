/*
    See lda-top/LICENCE (or http://elda.googlecode.com/hg/LICENCE)
    for the licence for this software.
    
    (c) Copyright 2011 Epimorphics Limited
    $Id$

    File:        Template.java
    Created by:  Dave Reynolds
    Created on:  4 Feb 2010
*/

package com.epimorphics.lda.core;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.epimorphics.lda.exceptions.EldaException;
import com.epimorphics.lda.rdfq.RDFQ;
import com.epimorphics.lda.shortnames.ShortnameService;
import com.epimorphics.lda.sources.Source;
import com.epimorphics.lda.support.Controls;
import com.epimorphics.lda.support.PrefixLogger;
import com.epimorphics.lda.support.PropertyChain;
import com.epimorphics.lda.support.Times;
import com.epimorphics.vocabs.API;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

/**
 * Represents a view which selects which parts of a result 
 * resource to show, defined by a (possibly) ordered list of properties.
 * 
 * @author <a href="mailto:der@epimorphics.com">Dave Reynolds</a>
 * @version $Revision: $
 */
public class View {
	
    static Logger log = LoggerFactory.getLogger(View.class);

    protected final List<PropertyChain> chains = new ArrayList<PropertyChain>();

    public static final String SHOW_ALL = "all";
    
    public static final String SHOW_BASIC = "basic";

    public static final String SHOW_DESCRIPTION = "description";
    
    public static final String SHOW_DEFAULT_INTERNAL = "default";
    
    protected static final List<PropertyChain> emptyChain = new ArrayList<PropertyChain>();

    /**
        View that does DESCRIBE plus labels of all objects.
    */
    public static final View ALL = new View( SHOW_ALL, Type.T_ALL );

	/**
	    Property chains: [RDF.type] and [RDFS.label].
	*/
	static final List<PropertyChain> BasicChains = 
		Arrays.asList( new PropertyChain( RDF.type ), new PropertyChain( RDFS.label ) );
    
	/**
        View that does rdf:type and rdfs:label.
    */
    // public static final View BASIC = new View( false, SHOW_BASIC, Type.T_BASIC );
    public static final View BASIC = new View( SHOW_BASIC, Type.T_CHAINS, BasicChains );
    
    /**
        View that does DESCRIBE.
    */
    public static final View DESCRIBE = new View( SHOW_DESCRIPTION, Type.T_DESCRIBE );
    
    private static Map<Resource, View> builtins = new HashMap<Resource, View>();
    
    static {
    	builtins.put( API.basicViewer, BASIC );
    	builtins.put( API.describeViewer, DESCRIBE );
    	builtins.put( API.labelledDescribeViewer, ALL );
    }
    
    /**
        Answer the built-in view with the given URI, or null if there
        isn't one.
    */
    public static View getBuiltin( Resource r ) {
    	return builtins.get(r);
    }
	
	public static enum Type { T_DESCRIBE, T_ALL, T_CHAINS };
	
	protected Type type = Type.T_DESCRIBE;
    
	protected String name = null;
	
	protected String labelPropertyURI = RDFS.label.getURI();
	
	protected int describeThreshold = 100;
	
    public View() {
    	this(true);
    }
    
    public View( Type type ) {
    	this( null, type );
    }
    
    public View( String name ) {
    	this( name, Type.T_CHAINS );
    }
    
    public View( boolean doesFiltering ) {
    	this( null, Type.T_DESCRIBE );
    }
    
    public View( String name, Type type ) {
    	this( name, type, emptyChain );
    }
    
    public View( String name, Type type, List<PropertyChain> initial ) {
    	this.type = type;
    	this.name = name;
    	this.chains.addAll( initial );
    }
    
    @Override public boolean equals( Object other ) {
    	return other instanceof View && same( (View) other );
    }
    
    private boolean same(View other) {
		return this.type == other.type && this.chains.equals( other.chains );
	}

	public String name(){
    	return name;
    }
    
    public Type getType() {
    	return type;
    }
    
    public Set<PropertyChain> chains() {
    	return new HashSet<PropertyChain>( chains );
    }
    
    /**
        Answer this view if it is ALL, otherwise a new view that
        does the same filtering and is mutable without affecting the
        original.
    */
    public View copy() {
    	View r = new View( null, type ).addFrom( this );
		return r;
    }

    /**
        Set the describe label used by this viewer. The viewer type
        becomes ALL.
    */
	public void setDescribeLabel( String labelPropertyURI ) {
		this.type = Type.T_ALL;
		this.labelPropertyURI = labelPropertyURI;
	}

	public void setDescribeThreshold( int threshold ) {
		this.describeThreshold = threshold;
	}
    
    /**
        Answer this view after modifying it to contain all the property
        chains defined by <code>spec</code>.
    */
    public View addViewFromRDFList(Resource spec, ShortnameService sns) {
    	cannotUpdateALL();		
        if (spec.canAs(RDFList.class)) {
        	List<Property> properties = new ArrayList<Property>();
            RDFList list = spec.as(RDFList.class);
            for (Iterator<RDFNode> i = list.iterator(); i.hasNext();) {
                properties.add( i.next().as( Property.class ) ); 
            }
            chains.add( new PropertyChain( properties ) );
        } else {
            String uri = spec.asResource().getURI();
            chains.add( new PropertyChain( uri ) );
        }
        if (chains.size() > 0) type = Type.T_CHAINS;
        return this;
    }

	private void cannotUpdateALL() {
		if (this == ALL) throw new IllegalArgumentException( "the view ALL cannot be updated." );
	}
    
    /**
        Answer this view after updating it with the given property string.
        The property name may be dotted; it defines a property chain.
    */
    public View addViewFromParameterValue( String prop, ShortnameService sns ) {
    	cannotUpdateALL();
        List<Property> chain = ShortnameService.Util.expandProperties( prop, sns );
        chains.add( new PropertyChain( chain ) );
        return this;
    }
    
    /**
        Answer this view after updating it by adding all the property chains
        of the argument view (which must not be null).
    */
    public View addFrom( View t ) {
    	if (t == null) throw new IllegalArgumentException( "addFrom does not accept null views" );
    	cannotUpdateALL();
    	chains.addAll( t.chains );
    	this.labelPropertyURI = t.labelPropertyURI;
    	this.describeThreshold = t.describeThreshold;
    	if (chains.size() > 0) type = Type.T_CHAINS;
    	if (t.type == Type.T_ALL) type = Type.T_ALL;
        return this;
    }
    
    /**
        Answer a string describing this view. It will show the list of
        property chains, possibly in an abbreviated form.
    */
    @Override public String toString() {
    	return type + " " + chains.toString().replaceAll( ",", ",\n  " );    	
    }
    
	public static class State {
		
		final String select;
		final List<Resource> roots;
		final Model m; 
		final List<Source> sources;
		final VarSupply vars;
		
		public State
			( String select
			, List<Resource> roots
			, Model m
			, List<Source> sources
			, VarSupply vars
			) {
			this.select = select;
			this.roots = roots;
			this.m = m; 
			this.sources = sources;
			this.vars = vars;
		}
	}
	
	public String fetchDescriptions( Controls c, State s ) {
		Times t = c.times;
		switch (type) {
			case T_DESCRIBE: {
				String detailsQuery = fetchByGivenPropertyChains( s, chains ); 
				String describeQuery = fetchUntimedByDescribe( s );
				t.addToViewQuerySize( detailsQuery );
				t.addToViewQuerySize( describeQuery );
				return describeQuery; 
			}
				
			case T_ALL:	{	
				String detailsQuery = fetchUntimedByDescribe( s ); 				
				String chainsQuery = fetchByGivenPropertyChains( s, chains ); 
			    addAllObjectLabels( c, s );
				t.addToViewQuerySize( detailsQuery );
				t.addToViewQuerySize( chainsQuery );
			    return detailsQuery;
			}

			case T_CHAINS:	{
				String detailsQuery = fetchByGivenPropertyChains( s, chains ); 
				t.addToViewQuerySize( detailsQuery );
				return detailsQuery;
			}
				
			default:
				EldaException.Broken( "unknown view type " + type );				
		}
		return "# should be a query here.";
	}
	
	private String fetchByGivenPropertyChains( State s, List<PropertyChain> chains ) { 
		if (chains.isEmpty()) 
			return "CONSTRUCT {} WHERE {}\n";
		boolean uns = useNestedSelect(s) && s.select.length() > 0;
		return uns // && false
			? fetchChainsByNestedSelect( s, chains ) 
			: fetchChainsByRepeatedClauses( s, chains )
			;
	}

	private boolean useNestedSelect( State st ) {
		return Source.Util.allSupportNestedSelect( st.sources );
	}
		
	private String fetchChainsByRepeatedClauses( State s, List<PropertyChain> chains ) { 
		ChainTrees chainTrees = new ChainTrees();
		for (Resource r: new HashSet<Resource>( s.roots )) {
			chainTrees.addAll( ChainTree.make( RDFQ.uri( r.getURI() ), s, chains ) );
		}
	//
		StringBuilder construct = new StringBuilder();
		PrefixLogger pl = new PrefixLogger( s.m );
		construct.append( "CONSTRUCT {\n" );
		chainTrees.renderTriples( construct, pl );
		construct.append( "\n} WHERE {\n" );
		chainTrees.renderWhere( construct, pl, "" );
		construct.append( "\n}" );
	//
		String prefixes = pl.writePrefixes( new StringBuilder() ).toString();
		String queryString = prefixes + construct.toString();
		Query constructQuery = QueryFactory.create( queryString );
		for (Source x: s.sources) s.m.add( x.executeConstruct( constructQuery ) );
		return queryString;
	}

	static final Pattern SELECT = Pattern.compile( "SELECT", Pattern.CASE_INSENSITIVE );
	
	private String fetchChainsByNestedSelect( State st, List<PropertyChain> chains ) { 
		PrefixLogger pl = new PrefixLogger( st.m );
		StringBuilder construct = new StringBuilder();
	//
		Matcher m = SELECT.matcher( st.select );
		if (!m.find()) EldaException.Broken( "No SELECT in nested query." );
		int s = m.start();
		String selection = st.select.substring( s );
		String selectPrefixes = st.select.substring(0, s);
		ChainTrees trees = ChainTree.make( RDFQ.var( "?item" ), st, chains );
	//
		construct.append( "CONSTRUCT {" );
		trees.renderTriples( construct, pl );
		construct.append( "\n} WHERE {\n" );			
		construct.append( "  {" ).append( selection.replaceAll( "\n", "\n    " ) ).append( "\n}" );
		trees.renderWhere( construct, pl, "" );			
		construct.append( "\n}" );
	//
		String prefixes = pl.writePrefixes( new StringBuilder() ).toString();
		String queryString = selectPrefixes + prefixes + construct.toString();
		// System.err.println( ">> QUERY:\n" + queryString );
		Query constructQuery = QueryFactory.create( queryString );
		for (Source x: st.sources) st.m.add( x.executeConstruct( constructQuery ) ); 
		return queryString;
	}
	
	private String fetchUntimedByDescribe( State s ) {
		List<Resource> allRoots = s.roots;
		boolean uns = useNestedSelect(s) && s.select.length() > 0;
	//
		if (uns && allRoots.size() > describeThreshold) {
			return describeByNestedSelect( s );
		} else {
			return describeBySelectedItems( s, allRoots );
		}
	}

	private String describeBySelectedItems(State s, List<Resource> allRoots) {
		StringBuilder describe = new StringBuilder();
		List<List<Resource>> chunks = chunkify( allRoots );
		for (List<Resource> roots: chunks) {
			PrefixLogger pl = new PrefixLogger( s.m );
			describe.setLength(0);
			describe.append( "DESCRIBE" );
			for (Resource r: new HashSet<Resource>( roots )) { // TODO
				describe.append( "\n  " ).append( pl.present( r.getURI() ) );
			}
			String query = pl.writePrefixes( new StringBuilder() ).toString() + describe;
			Query describeQuery = QueryFactory.create( query );
			for (Source x: s.sources) s.m.add( x.executeDescribe( describeQuery ) );
		}
		return describe.toString();
	}

	private String describeByNestedSelect( State s ) {
		StringBuilder describe = new StringBuilder();
		Matcher m = SELECT.matcher( s.select );
		if (!m.find()) EldaException.Broken( "No SELECT in nested query." );
		int start = m.start();
		String selection = s.select.substring( start );
		String selectPrefixes = s.select.substring(0, start);
		describe.append( "DESCRIBE ?item" );
		PrefixLogger pl = new PrefixLogger( s.m );
		describe.append( "\n WHERE {\n" );			
		describe.append( "  {" ).append( selection.replaceAll( "\n", "\n    " ) ).append( "\n}" );
		describe.append( "\n}" );			
		String query = 
			selectPrefixes
			+ pl.writePrefixes( new StringBuilder() ).toString() 
			+ describe
			;
		Query describeQuery = QueryFactory.create( query );
		for (Source x: s.sources) s.m.add( x.executeDescribe( describeQuery ) );
		return describe.toString();
	}		
	
	static final int CHUNK_SIZE = 1000;
	static final boolean slice_describes = false;
	
	private List<List<Resource>> chunkify(List<Resource> roots) {
		List<List<Resource>> result = new ArrayList<List<Resource>>();
		if (slice_describes) {
			int size = roots.size();
			for (int i = 0; i < size; i += CHUNK_SIZE) {
				int lim = Math.min( size, i + CHUNK_SIZE );
				result.add( roots.subList( i, lim ) );
			}
			if (result.size() > 1)
				log.debug( "large DESCRIBE: " + result.size() + " chunks of size " + CHUNK_SIZE );
		} else {
			result.add( roots );
		}
		return result;
	}

	private void addAllObjectLabels( Controls c, State s ) { 
		StringBuilder sb = new StringBuilder();
		sb.append( "PREFIX rdfs: <" ).append( RDFS.getURI() ).append(">" )
			.append( "\nCONSTRUCT { ?x <" ).append( labelPropertyURI ).append( "> ?l }\nWHERE\n{" );
		String union = "";
		for (RDFNode n: s.m.listObjects().toList()) {
			if (n.isURIResource()) {
				sb.append( union )
					.append( "{?x <" ).append( labelPropertyURI ).append( "> ?l. " )
					.append( "FILTER(?x = <" ).append( n.asNode().getURI() ).append( ">)" )
					.append( "}" );
				union = "\nUNION ";
			}
		}
		sb.append( "}\n" );
		String queryString = sb.toString();
		Query constructQuery = QueryFactory.create( queryString );
		for (Source x: s.sources) s.m.add( x.executeConstruct( constructQuery ) );
	}
}

