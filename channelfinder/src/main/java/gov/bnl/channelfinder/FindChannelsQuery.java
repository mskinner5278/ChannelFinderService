/*
 * Copyright (c) 2010 Brookhaven National Laboratory
 * Copyright (c) 2010 Helmholtz-Zentrum Berlin für Materialien und Energie GmbH
 * Subject to license terms and conditions.
 */
package gov.bnl.channelfinder;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

/**
 *  JDBC query to retrieve channels from the directory .
 *
 * @author Ralph Lange <Ralph.Lange@bessy.de>
 */
public class FindChannelsQuery {

    private enum SearchType {

        CHANNEL, TAG
    };
    private Multimap<String, String> prop_matches = ArrayListMultimap.create();
    private List<String> chan_matches = new ArrayList();
    private List<String> tag_matches = new ArrayList();
    private List<String> tag_patterns = new ArrayList();

    private void addTagMatches(Collection<String> matches) {
        for (String m : matches) {
            if (m.contains("?") || m.contains("*")) {
                tag_patterns.add(m);
            } else {
                tag_matches.add(m);
            }
        }
    }

    /**
     * Creates a new instance of FindChannelsQuery, sorting the query parameters.
     * Property matches and tag string matches go to the first inner query,
     * tag pattern matches are queried separately,
     * name matches go to the outer query.
     * Property and tag names are converted to lowercase before being matched.
     *
     * @param matches  the map of matches to apply
     */
    private FindChannelsQuery(MultivaluedMap<String, String> matches) {
        for (Map.Entry<String, List<String>> match : matches.entrySet()) {
            String key = match.getKey().toLowerCase();
            if (key.equals("~name")) {
                chan_matches.addAll(match.getValue());
            } else if (key.equals("~tag")) {
                addTagMatches(match.getValue());
            } else {
                prop_matches.putAll(key, match.getValue());
            }
        }
    }

    /**
     * Creates a new instance of FindChannelsQuery for single type query.
     *
     * @param matches  the collection of name matches
     */
    private FindChannelsQuery(SearchType type, Collection<String> matches) {
        if (type == SearchType.CHANNEL) {
            chan_matches.addAll(matches);
        } else {
            addTagMatches(matches);
        }
    }

    /**
     * Creates a new instance of FindChannelsQuery for a simple single type query.
     *
     * @param name the name to match
     */
    private FindChannelsQuery(SearchType type, String name) {
        if (type == SearchType.CHANNEL) {
            chan_matches.add(name);
        } else {
            addTagMatches(Collections.singleton(name));
        }
    }

    /**
     * Creates a generic FindChannelsQuery (mix of channel, tag, and property search)
     * @param matches MultiMap of query parameters
     * @return new FindChannelsQuery instance
     */
    public static FindChannelsQuery createMultiMatchQuery(MultivaluedMap<String, String> matches) {
        return new FindChannelsQuery(matches);
    }

    /**
     * Creates a channel FindChannelsQuery (multiple channel names)
     * @param matches String collection of name query parameters
     * @return new FindChannelsQuery instance
     */
    public static FindChannelsQuery createChannelMatchQuery(Collection<String> matches) {
        return new FindChannelsQuery(SearchType.CHANNEL, matches);
    }

    /**
     * Creates a tag FindChannelsQuery (multiple tag names)
     * @param matches String collection of name query parameters
     * @return new FindChannelsQuery instance
     */
    public static FindChannelsQuery createTagMatchQuery(Collection<String> matches) {
        return new FindChannelsQuery(SearchType.TAG, matches);
    }

    /**
     * Creates and executes the property and tag string match subquery using GROUP.
     *
     * @param con connection to use
     * @return a set of channel ids that match
     */
    private Set<Long> getIdsFromPropertyMatch(Connection con) throws CFException {
        StringBuilder query = new StringBuilder("SELECT p0.channel_id FROM property p0 WHERE");
        Set<Long> ids = new HashSet<Long>();           // set of matching channel ids
        List<String> params = new ArrayList<String>(); // parameter list for this query

        for (Map.Entry<String, Collection<String>> match : prop_matches.asMap().entrySet()) {
            StringBuilder valueList = new StringBuilder("p0.value LIKE");
            params.add(match.getKey().toLowerCase());
            for (String value : match.getValue()) {
                valueList.append(" ? OR p0.value LIKE");
                params.add(convertFileGlobToSQLPattern(value));
            }
            query.append(" (LOWER(p0.property) = ? AND ("
                    + valueList.substring(0, valueList.length() - 17) + ")) OR");
        }

        for (String tag : tag_matches) {
            params.add(convertFileGlobToSQLPattern(tag).toLowerCase());
            query.append(" (LOWER(p0.property) = ? AND p0.value IS NULL) OR");
        }

        query.replace(query.length() - 2, query.length(),
                "GROUP BY p0.channel_id HAVING COUNT(p0.channel_id) = ?");

        try {
            PreparedStatement ps = con.prepareStatement(query.toString());
            int i = 1;
            for (String p : params) {
                ps.setString(i++, p);
            }
            ps.setLong(i++, prop_matches.asMap().size()  + tag_matches.size());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                // Add key to list of matching channel ids
                ids.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new CFException(Response.Status.INTERNAL_SERVER_ERROR,
                    "SQL Exception while getting ids in property match query", e);
        }
        return ids;
    }

    /**
     * Creates and executes the property and tag string match subquery using GROUP.
     *
     * @param con connection to use
     * @return a set of channel ids that match
     */
    private Set<Long> getIdsFromTagMatch(Connection con, String match) throws CFException {
        String query = "SELECT p0.channel_id FROM property p0"
                + " WHERE LOWER(p0.property) LIKE ? AND p0.value IS NULL"
                + " GROUP BY p0.channel_id";
        Set<Long> ids = new HashSet<Long>();

        try {
            PreparedStatement ps = con.prepareStatement(query);
            ps.setString(1, convertFileGlobToSQLPattern(match));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new CFException(Response.Status.INTERNAL_SERVER_ERROR,
                    "SQL Exception while getting ids in tag match query", e);
        }
        return ids;
    }

    /**
     * Creates and executes a JDBC based query using subqueries for
     * property and tag matches.
     *
     * @param con  connection to use
     * @return result set with columns named <tt>channel</tt>, <tt>property</tt>,
     *         <tt>value</tt>, null if no results
     * @throws CFException wrapping an SQLException
     */
    public ResultSet executeQuery(Connection con) throws CFException {
        StringBuilder query = new StringBuilder("SELECT c.name as channel, c.owner as cowner, p.property, p.value, p.owner"
                + " FROM channel c LEFT JOIN property p ON c.id = p.channel_id WHERE 1=1");
        List<Long> id_params = new ArrayList<Long>();       // parameter lists for the outer query
        List<String> name_params = new ArrayList<String>();
        Set<Long> result = new HashSet<Long>();

        if (!prop_matches.isEmpty() || !tag_matches.isEmpty()) {
            Set<Long> ids = getIdsFromPropertyMatch(con);
            if (ids.isEmpty()) {
                return null;
            }
            result = ids;
        }

        if (!tag_patterns.isEmpty()) {
            for (String p : tag_patterns) {
                Set<Long> ids = getIdsFromTagMatch(con, p);
                if (ids.isEmpty()) {
                    return null;
                }
                if (result.isEmpty()) {
                    result = ids;
                } else {
                    result.retainAll(ids);
                    if (result.isEmpty()) {
                        return null;
                    }
                }
            }
        }

        if (!result.isEmpty()) {
            query.append(" AND c.id IN (");
            for (long i : result) {
                query.append("?,");
                id_params.add(i);
            }
            query.replace(query.length() - 1, query.length(), ")");
        }

        if (!chan_matches.isEmpty()) {
            query.append(" AND (");
            for (String value : chan_matches) {
                query.append("c.name LIKE ? OR ");
                name_params.add(convertFileGlobToSQLPattern(value));
            }
            query.replace(query.length() - 4, query.length(), ")");
        }

        query.append(" ORDER BY channel, property");

        try {
            PreparedStatement ps = con.prepareStatement(query.toString());
            int i = 1;
            for (long p : id_params) {
                ps.setLong(i++, p);
            }
            for (String s : name_params) {
                ps.setString(i++, s);
            }

            return ps.executeQuery();
        } catch (SQLException e) {
            throw new CFException(Response.Status.INTERNAL_SERVER_ERROR,
                    "SQL Exception in channels query", e);
        }
    }

    /* Regexp for this pattern: "((\\\\)*)((\\\*)|(\*)|(\\\?)|(\?)|(%)|(_))"
     * i.e. any number of "\\" (group 1) -> same number of "\\"
     * then any of        "\*" (group 4) -> "*"
     *                    "*"  (group 5) -> "%"
     *                    "\?" (group 6) -> "?"
     *                    "?"  (group 7) -> "_"
     *                    "%"  (group 8) -> "\%"
     *                    "_"  (group 9) -> "\_"
     */
    private static Pattern pat = Pattern.compile("((\\\\\\\\)*)((\\\\\\*)|(\\*)|(\\\\\\?)|(\\?)|(%)|(_))");
    private static final int grp[] = {4, 5, 6, 7, 8, 9};
    private static final String rpl[] = {"*", "%", "?", "_", "\\%", "\\_"};

    /**
     * Translates the specified file glob pattern <tt>in</tt>
     * into the corresponding SQL pattern.
     *
     * @param in  file glob pattern
     * @return  SQL pattern
     */
    private static String convertFileGlobToSQLPattern(String in) {
        StringBuffer out = new StringBuffer();
        Matcher m = pat.matcher(in);

        while (m.find()) {
            StringBuffer rep = new StringBuffer();
            if (m.group(1) != null) {
                rep.append(m.group(1));
            }
            for (int i = 0; i < grp.length; i++) {
                if (m.group(grp[i]) != null) {
                    rep.append(rpl[i]);
                    break;
                }
            }
            m.appendReplacement(out, rep.toString());
        }
        m.appendTail(out);
        return out.toString();
    }
}
