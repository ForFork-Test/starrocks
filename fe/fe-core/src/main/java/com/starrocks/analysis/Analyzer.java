// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/analysis/Analyzer.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.analysis;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.OlapTable.OlapTableState;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Table.TableType;
import com.starrocks.catalog.Type;
import com.starrocks.catalog.View;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repository of analysis state for single select block.
 * <p/>
 * All conjuncts are assigned a unique id when initially registered, and all
 * registered conjuncts are referenced by their id (ie, there are no containers
 * other than the one holding the referenced conjuncts), to make substitute()
 * simple.
 */
@Deprecated
public class Analyzer {
    // NOTE: Alias of table is case sensitive
    // UniqueAlias used to check wheather the table ref or the alias is unique
    // table/view used db.table, inline use alias
    private final Set<String> uniqueTableAliasSet_ = Sets.newHashSet();
    private final Multimap<String, TupleDescriptor> tupleByAlias = ArrayListMultimap.create();

    // NOTE: Alias of column is case ignorance
    // map from lowercase table alias to descriptor.
    // protected final Map<String, TupleDescriptor> aliasMap             = Maps.newHashMap();
    // map from lowercase qualified column name ("alias.col") to descriptor
    private final Map<String, SlotDescriptor> slotRefMap = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);

    // Current depth of nested analyze() calls. Used for enforcing a
    // maximum expr-tree depth. Needs to be manually maintained by the user
    // of this Analyzer with incrementCallDepth() and decrementCallDepth().
    private int callDepth = 0;

    // timezone specified for some operation, such as broker load
    private String timezone = TimeUtils.DEFAULT_TIME_ZONE;

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getTimezone() {
        return timezone;
    }

    // state shared between all objects of an Analyzer tree
    // TODO: Many maps here contain properties about tuples, e.g., whether
    // a tuple is outer/semi joined, etc. Remove the maps in favor of making
    // them properties of the tuple descriptor itself.
    private static class GlobalState {
        private final DescriptorTable descTbl = new DescriptorTable();
        private final GlobalStateMgr globalStateMgr;
        private final ConnectContext context;

        // True if we are analyzing an explain request. Should be set before starting
        // analysis.
        public boolean isExplain;

        // all registered conjuncts (map from id to Predicate)
        private final Map<ExprId, Expr> conjuncts = Maps.newHashMap();

        // set of conjuncts that have been assigned to some PlanNode
        private final Set<ExprId> assignedConjuncts =
                Collections.newSetFromMap(new IdentityHashMap<ExprId, Boolean>());

        // map from outer-joined tuple id, ie, one that is nullable in this select block,
        // to the last Join clause (represented by its rhs table ref) that outer-joined it
        private final Map<TupleId, TableRef> outerJoinedTupleIds = Maps.newHashMap();

        // Map from semi-joined tuple id, i.e., one that is invisible outside the join's
        // On-clause, to its Join clause (represented by its rhs table ref). An anti-join is
        // a kind of semi-join, so anti-joined tuples are also registered here.
        public final Map<TupleId, TableRef> semiJoinedTupleIds = Maps.newHashMap();

        // map from registered conjunct to its containing outer join On clause (represented
        // by its right-hand side table ref); only conjuncts that can only be correctly
        // evaluated by the originating outer join are registered here
        private final Map<ExprId, TableRef> ojClauseByConjunct = Maps.newHashMap();

        // TODO chenhao16, to save conjuncts, which children are constant
        public final Map<TupleId, Set<ExprId>> constantConjunct = Maps.newHashMap();

        // map from slot id to the analyzer/block in which it was registered
        public final Map<SlotId, Analyzer> blockBySlot = Maps.newHashMap();

        public GlobalState(GlobalStateMgr globalStateMgr, ConnectContext context) {
            this.globalStateMgr = globalStateMgr;
            this.context = context;
        }
    }

    private final GlobalState globalState;

    // An analyzer stores analysis state for a single select block. A select block can be
    // a top level select statement, or an inline view select block.
    // ancestors contains the Analyzers of the enclosing select blocks of 'this'
    // (ancestors[0] contains the immediate parent, etc.).
    private final ArrayList<Analyzer> ancestors;

    // map from lowercase table alias to a view definition in this analyzer's scope
    private final Map<String, View> localViews_ = Maps.newHashMap();

    // Map from lowercase table alias to descriptor. Tables without an explicit alias
    // are assigned two implicit aliases: the unqualified and fully-qualified table name.
    // Such tables have two entries pointing to the same descriptor. If an alias is
    // ambiguous, then this map retains the first entry with that alias to simplify error
    // checking (duplicate vs. ambiguous alias).
    private final Map<String, TupleDescriptor> aliasMap_ = Maps.newHashMap();

    // Map from tuple id to its corresponding table ref.
    private final Map<TupleId, TableRef> tableRefMap_ = Maps.newHashMap();

    // Set of lowercase ambiguous implicit table aliases.
    private final Set<String> ambiguousAliases_ = Sets.newHashSet();

    public Analyzer(GlobalStateMgr globalStateMgr, ConnectContext context) {
        ancestors = Lists.newArrayList();
        globalState = new GlobalState(globalStateMgr, context);
    }

    public void setIsExplain() {
        globalState.isExplain = true;
    }

    public boolean isExplain() {
        return globalState.isExplain;
    }

    public int incrementCallDepth() {
        return ++callDepth;
    }

    public int decrementCallDepth() {
        return --callDepth;
    }

    public int getCallDepth() {
        return callDepth;
    }

    /**
     * Creates an returns an empty TupleDescriptor for the given table ref and registers
     * it against all its legal aliases. For tables refs with an explicit alias, only the
     * explicit alias is legal. For tables refs with no explicit alias, the fully-qualified
     * and unqualified table names are legal aliases. Column references against unqualified
     * implicit aliases can be ambiguous, therefore, we register such ambiguous aliases
     * here. Requires that all views have been substituted.
     * Throws if an existing explicit alias or implicit fully-qualified alias
     * has already been registered for another table ref.
     */
    public TupleDescriptor registerTableRef(TableRef ref) throws AnalysisException {
        String uniqueAlias = ref.getUniqueAlias();
        if (uniqueTableAliasSet_.contains(uniqueAlias)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_NONUNIQ_TABLE, uniqueAlias);
        }
        uniqueTableAliasSet_.add(uniqueAlias);

        // If ref has no explicit alias, then the unqualified and the fully-qualified table
        // names are legal implicit aliases. Column references against unqualified implicit
        // aliases can be ambiguous, therefore, we register such ambiguous aliases here.
        String unqualifiedAlias = null;
        String[] aliases = ref.getAliases();
        if (aliases.length > 1) {
            unqualifiedAlias = aliases[1];
            TupleDescriptor tupleDesc = aliasMap_.get(unqualifiedAlias);
            if (tupleDesc != null) {
                if (tupleDesc.hasExplicitAlias()) {
                    ErrorReport.reportAnalysisException(ErrorCode.ERR_NONUNIQ_TABLE, uniqueAlias);
                } else {
                    ambiguousAliases_.add(unqualifiedAlias);
                }
            }
        }

        // Delegate creation of the tuple descriptor to the concrete table ref.
        TupleDescriptor result = ref.createTupleDescriptor(this);
        result.setRef(ref);
        result.setAliases(aliases, ref.hasExplicitAlias());

        // Register all legal aliases.
        for (String alias : aliases) {
            // TODO(zc)
            // aliasMap_.put(alias, result);
            tupleByAlias.put(alias, result);
        }
        tableRefMap_.put(result.getId(), ref);

        return result;
    }

    /**
     * Resolves the given TableRef into a concrete BaseTableRef, ViewRef or
     * CollectionTableRef. Returns the new resolved table ref or the given table
     * ref if it is already resolved.
     * Registers privilege requests and throws an AnalysisException if the tableRef's
     * path could not be resolved. The privilege requests are added to ensure that
     * an AuthorizationException is preferred over an AnalysisException so as not to
     * accidentally reveal the non-existence of tables/databases.
     * <p>
     * TODO(zc): support collection table ref
     */
    public TableRef resolveTableRef(TableRef tableRef) throws AnalysisException {
        // Return the table if it is already resolved.
        if (tableRef.isResolved()) {
            return tableRef;
        }
        // Try to find a matching local view.
        TableName tableName = tableRef.getName();
        if (!tableName.isFullyQualified()) {
            // Searches the hierarchy of analyzers bottom-up for a registered local view with
            // a matching alias.
            String viewAlias = tableName.getTbl();
            Analyzer analyzer = this;
            do {
                analyzer = (analyzer.ancestors.isEmpty() ? null : analyzer.ancestors.get(0));
            } while (analyzer != null);
        }

        // Resolve the table ref's path and determine what resolved table ref
        // to replace it with.
        String dbName = tableName.getDb();
        if (Strings.isNullOrEmpty(dbName)) {
            dbName = getDefaultDb();
        }
        if (Strings.isNullOrEmpty(dbName)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_NO_DB_ERROR);
        }

        Database database = globalState.globalStateMgr.getDb(dbName);
        if (database == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }

        Table table = database.getTable(tableName.getTbl());
        if (table == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_TABLE_ERROR, tableName.getTbl());
        }

        if (table.getType() == TableType.OLAP && (((OlapTable) table).getState() == OlapTableState.RESTORE
                || ((OlapTable) table).getState() == OlapTableState.RESTORE_WITH_LOAD)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_TABLE_STATE, "RESTORING");
        }

        TableName tblName = new TableName(database.getFullName(), table.getName());
        // The table must be a base table.
        return new BaseTableRef(tableRef, table, tblName);
    }

    public Table getTable(TableName tblName) {
        Database db = globalState.globalStateMgr.getDb(tblName.getDb());
        if (db == null) {
            return null;
        }
        return db.getTable(tblName.getTbl());
    }

    public TupleDescriptor getTupleDesc(TupleId id) {
        return globalState.descTbl.getTupleDesc(id);
    }

    /**
     * Register a virtual column, and it is not a real column exist in table,
     * so it does not need to resolve.
     */
    public SlotDescriptor registerVirtualColumnRef(String colName, Type type, TupleDescriptor tupleDescriptor)
            throws AnalysisException {
        // Make column name case insensitive
        String key = colName;
        SlotDescriptor result = slotRefMap.get(key);
        if (result != null) {
            result.setMultiRef(true);
            return result;
        }

        result = addSlotDescriptor(tupleDescriptor);
        Column col = new Column(colName, type);
        result.setColumn(col);
        result.setIsNullable(true);
        slotRefMap.put(key, result);
        return result;
    }

    /**
     * Creates a new slot descriptor and related state in globalState.
     */
    public SlotDescriptor addSlotDescriptor(TupleDescriptor tupleDesc) {
        SlotDescriptor result = globalState.descTbl.addSlotDescriptor(tupleDesc);
        globalState.blockBySlot.put(result.getId(), this);
        return result;
    }

    /**
     * Return all unassigned registered conjuncts that are fully bound by the given
     * (logical) tuple ids, can be evaluated by 'tupleIds' and are not tied to an
     * Outer Join clause.
     */
    public List<Expr> getUnassignedConjuncts(List<TupleId> tupleIds) {
        List<Expr> result = Lists.newArrayList();
        for (Expr e : getUnassignedConjuncts(tupleIds, true)) {
            if (canEvalPredicate(tupleIds, e)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Return all unassigned non-constant registered conjuncts that are fully bound by
     * given list of tuple ids. If 'inclOjConjuncts' is false, conjuncts tied to an
     * Outer Join clause are excluded.
     */
    public List<Expr> getUnassignedConjuncts(
            List<TupleId> tupleIds, boolean inclOjConjuncts) {
        List<Expr> result = Lists.newArrayList();
        for (Expr e : globalState.conjuncts.values()) {
            // handle constant conjuncts
            if (e.isConstant()) {
                boolean isBoundByTuple = false;
                for (TupleId id : tupleIds) {
                    final Set<ExprId> exprSet = globalState.constantConjunct.get(id);
                    if (exprSet != null && exprSet.contains(e.id)) {
                        isBoundByTuple = true;
                        break;
                    }
                }
                if (!isBoundByTuple) {
                    continue;
                }
            }
            if (e.isBoundByTupleIds(tupleIds)
                    && !e.isAuxExpr()
                    && !globalState.assignedConjuncts.contains(e.getId())
                    && ((inclOjConjuncts && !e.isConstant())
                    || !globalState.ojClauseByConjunct.containsKey(e.getId()))) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Returns the fully-qualified table name of tableName. If tableName
     * is already fully qualified, returns tableName.
     */
    public TableName getFqTableName(TableName tableName) {
        if (tableName.isFullyQualified()) {
            return tableName;
        }
        return new TableName(getDefaultDb(), tableName.getTbl());
    }

    /**
     * Return rhs ref of last Join clause that outer-joined id.
     */
    public TableRef getLastOjClause(TupleId id) {
        return globalState.outerJoinedTupleIds.get(id);
    }

    public DescriptorTable getDescTbl() {
        return globalState.descTbl;
    }

    public GlobalStateMgr getCatalog() {
        return globalState.globalStateMgr;
    }

    public Set<String> getAliases() {
        return uniqueTableAliasSet_;
    }

    /**
     * Mark predicates as assigned.
     */
    public void markConjunctsAssigned(List<Expr> conjuncts) {
        if (conjuncts == null) {
            return;
        }
        for (Expr p : conjuncts) {
            globalState.assignedConjuncts.add(p.getId());
        }
    }

    public String getDefaultDb() {
        return globalState.context.getDatabase();
    }

    public String getDefaultCatalog() {
        return globalState.context.getCurrentCatalog();
    }

    public String getQualifiedUser() {
        return globalState.context.getQualifiedUser();
    }

    public ConnectContext getContext() {
        return globalState.context;
    }

    /**
     * Returns true if predicate 'e' can be correctly evaluated by a tree materializing
     * 'tupleIds', otherwise false:
     * - The predicate needs to be bound by tupleIds.
     * - For On-clause predicates:
     * - If the predicate is from an anti-join On-clause it must be evaluated by the
     * corresponding anti-join node.
     * - Predicates from the On-clause of an inner or semi join are evaluated at the
     * node that materializes the required tuple ids, unless they reference outer
     * joined tuple ids. In that case, the predicates are evaluated at the join node
     * of the corresponding On-clause.
     * - Predicates referencing full-outer joined tuples are assigned at the originating
     * join if it is a full-outer join, otherwise at the last full-outer join that does
     * not materialize the table ref ids of the originating join.
     * - Predicates from the On-clause of a left/right outer join are assigned at
     * the corresponding outer join node with the exception of simple predicates
     * that only reference a single tuple id. Those may be assigned below the
     * outer join node if they are from the same On-clause that makes the tuple id
     * nullable.
     * - Otherwise, a predicate can only be correctly evaluated if for all outer-joined
     * referenced tids the last join to outer-join this tid has been materialized.
     */
    public boolean canEvalPredicate(List<TupleId> tupleIds, Expr e) {
        if (!e.isBoundByTupleIds(tupleIds)) {
            return false;
        }
        ArrayList<TupleId> ids = Lists.newArrayList();
        e.getIds(ids, null);
        Set<TupleId> tids = Sets.newHashSet(ids);
        if (tids.isEmpty()) {
            return true;
        }

        for (TupleId tid : tids) {
            TableRef rhsRef = getLastOjClause(tid);
            // this is not outer-joined; ignore
            if (rhsRef == null) {
                continue;
            }
            // check whether the last join to outer-join 'tid' is materialized by tupleIds
            if (!tupleIds.containsAll(rhsRef.getAllTupleIds())) {
                return false;
            }
        }

        return true;
    }
}
