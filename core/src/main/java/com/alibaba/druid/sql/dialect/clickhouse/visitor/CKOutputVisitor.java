package com.alibaba.druid.sql.dialect.clickhouse.visitor;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.clickhouse.ast.CKAlterTableUpdateStatement;
import com.alibaba.druid.sql.dialect.clickhouse.ast.CKCreateTableStatement;
import com.alibaba.druid.sql.dialect.clickhouse.ast.CKSelectQueryBlock;
import com.alibaba.druid.sql.dialect.clickhouse.ast.ClickhouseColumnCodec;
import com.alibaba.druid.sql.dialect.clickhouse.ast.ClickhouseColumnTTL;
import com.alibaba.druid.sql.parser.CharTypes;
import com.alibaba.druid.sql.visitor.SQLASTOutputVisitor;
import com.alibaba.druid.sql.visitor.VisitorFeature;
import com.alibaba.druid.util.StringUtils;

import java.util.List;

public class CKOutputVisitor extends SQLASTOutputVisitor implements CKASTVisitor {
    public CKOutputVisitor(StringBuilder appender) {
        super(appender, DbType.clickhouse);
    }

    public CKOutputVisitor(StringBuilder appender, DbType dbType) {
        super(appender, dbType);
    }

    public CKOutputVisitor(StringBuilder appender, boolean parameterized) {
        super(appender, DbType.clickhouse, parameterized);
    }

    @Override
    public boolean visit(SQLWithSubqueryClause.Entry x) {
        if (x.isPrefixAlias()) {
            print0(x.getAlias());
            print(' ');
            print0(ucase ? "AS " : "as ");
            printWithExpr(x);
        } else {
            printWithExpr(x);
            print(' ');
            print0(ucase ? "AS " : "as ");
            print0(x.getAlias());
        }

        return false;
    }

    private void printWithExpr(SQLWithSubqueryClause.Entry x) {
        if (x.getExpr() != null) {
            x.getExpr().accept(this);
        } else if (x.getSubQuery() != null) {
            print('(');
            println();
            SQLSelect query = x.getSubQuery();
            if (query != null) {
                query.accept(this);
            } else {
                x.getReturningStatement().accept(this);
            }
            println();
            print(')');
        }
    }

    public boolean visit(SQLStructDataType x) {
        print0(ucase ? "NESTED (" : "nested (");
        incrementIndent();
        println();
        printlnAndAccept(x.getFields(), ",");
        decrementIndent();
        println();
        print(')');
        return false;
    }

    @Override
    public boolean visit(SQLStructDataType.Field x) {
        SQLName name = x.getName();
        if (name != null) {
            name.accept(this);
        }
        SQLDataType dataType = x.getDataType();

        if (dataType != null) {
            print(' ');
            dataType.accept(this);
        }

        return false;
    }

    @Override
    public boolean visit(SQLPartitionByList x) {
        if (x.getColumns().size() == 1) {
            x.getColumns().get(0).accept(this);
        } else {
            print('(');
            printAndAccept(x.getColumns(), ", ");
            print0(")");
        }

        printPartitionsCountAndSubPartitions(x);

        printSQLPartitions(x.getPartitions());
        return false;
    }

    @Override
    protected void printCreateTable(SQLCreateTableStatement x, boolean printSelect) {
        print0(ucase ? "CREATE " : "create ");

        printCreateTableFeatures(x);

        print0(ucase ? "TABLE " : "table ");

        if (x.isIfNotExists()) {
            print0(ucase ? "IF NOT EXISTS " : "if not exists ");
        }

        printTableSourceExpr(
                x.getTableSource()
                        .getExpr());

        printCreateTableAfterName(x);
        printTableElements(x.getTableElementList());
        printPartitionedBy(x);
        printClusteredBy(x);
        printCreateTableLike(x);

        printSelectAs(x, printSelect);
    }
    @Override
    public boolean visit(CKCreateTableStatement x) {
        super.visit((SQLCreateTableStatement) x);

//        SQLPartitionBy partitionBy = x.getPartitioning();
//        if (partitionBy != null) {
//            println();
//            print0(ucase ? "PARTITION BY " : "partition by ");
//            partitionBy.accept(this);
//        }

        SQLOrderBy orderBy = x.getOrderBy();
        if (orderBy != null) {
            println();
            orderBy.accept(this);
        }

        SQLPrimaryKey primaryKey = x.getPrimaryKey();
        if (primaryKey != null) {
            println();
            primaryKey.accept(this);
        }

        SQLExpr sampleBy = x.getSampleBy();
        if (sampleBy != null) {
            println();
            print0(ucase ? "SAMPLE BY " : "sample by ");
            sampleBy.accept(this);
        }

        SQLExpr ttl = x.getTtl();
        if (ttl != null) {
            println();
            print0(ucase ? "TTL " : "ttl ");
            ttl.accept(this);
        }

        List<SQLAssignItem> settings = x.getSettings();
        if (!settings.isEmpty()) {
            println();
            print0(ucase ? "SETTINGS " : "settings ");
            printAndAccept(settings, ", ");
        }
        printComment(x.getComment());
        return false;
    }

    public boolean visit(SQLAlterTableAddColumn x) {
        print0(ucase ? "ADD COLUMN " : "add column ");
        printAndAccept(x.getColumns(), ", ");
        return false;
    }

    @Override
    public boolean visit(CKAlterTableUpdateStatement x) {
        print0(ucase ? "ALTER TABLE " : "alter table ");
        printExpr(x.getTableName());
        if (x.getClusterName() != null) {
            print0(ucase ? " ON CLUSTER " : " on cluster ");
            if (parameterized) {
                print('?');
            } else {
                printExpr(x.getClusterName());
            }
        }
        print0(ucase ? " UPDATE " : " update ");
        for (int i = 0, size = x.getItems().size(); i < size; ++i) {
            if (i != 0) {
                print0(", ");
            }
            SQLUpdateSetItem item = x.getItems().get(i);
            visit(item);
        }
        if (x.getPartitionId() != null) {
            print0(ucase ? " IN PARTITION " : " in partition ");
            if (parameterized) {
                print('?');
            } else {
                printExpr(x.getPartitionId());
            }
        }
        if (x.getWhere() != null) {
            print0(ucase ? " WHERE " : " where ");
            x.getWhere().accept(this);
        }

        return false;
    }

    @Override
    public boolean visit(ClickhouseColumnCodec x) {
        print0(ucase ? "CODEC(" : "codec(");
        printExpr(x.getExpr());
        print(")");
        return false;
    }

    public boolean visit(ClickhouseColumnTTL x) {
        print0(ucase ? " TTL " : " ttl ");
        printExpr(x.getExpr());
        return false;
    }

    @Override
    public void printComment(String comment) {
        if (comment == null) {
            return;
        }

        if (isEnabled(VisitorFeature.OutputSkipMultilineComment) && comment.startsWith("/*")) {
            return;
        }

        if (isEnabled(VisitorFeature.OutputSkipSingleLineComment)
                && (comment.startsWith("-") || comment.startsWith("#"))) {
            return;
        }

        if (comment.startsWith("--")
                && comment.length() > 2
                && comment.charAt(2) != ' '
                && comment.charAt(2) != '-') {
            print0("-- ");
            print0(comment.substring(2));
        } else if (comment.startsWith("#")
                && comment.length() > 1
                && comment.charAt(1) != ' '
                && comment.charAt(1) != '#') {
            print0("# ");
            print0(comment.substring(1));
        } else if (comment.startsWith("/*")) {
            println();
            print0(comment);
        } else if (comment.startsWith("--")) {
            print0(comment);
        }

        char first = '\0';
        for (int i = 0; i < comment.length(); i++) {
            char c = comment.charAt(i);
            if (CharTypes.isWhitespace(c)) {
                continue;
            }
            first = c;
            break;
        }

        if (first == '-' || first == '#') {
            endLineComment = true;
        }
    }

    @Override
    protected void printAfterFetch(SQLSelectQueryBlock queryBlock) {
        if (queryBlock instanceof CKSelectQueryBlock) {
            CKSelectQueryBlock ckSelectQueryBlock = ((CKSelectQueryBlock) queryBlock);
            if (!ckSelectQueryBlock.getSettings().isEmpty()) {
                println();
                print0(ucase ? "SETTINGS " : "settings ");
                printAndAccept(ckSelectQueryBlock.getSettings(), ", ");
            }
            if (ckSelectQueryBlock.getFormat() != null) {
                println();
                print0(ucase ? "FORMAT " : "format ");
                ckSelectQueryBlock.getFormat().accept(this);
            }
        }
    }

    protected void printWhere(SQLSelectQueryBlock queryBlock) {
        if (queryBlock instanceof CKSelectQueryBlock) {
            SQLExpr preWhere = ((CKSelectQueryBlock) queryBlock).getPreWhere();
            if (preWhere != null) {
                println();
                print0(ucase ? "PREWHERE " : "prewhere ");
                printExpr(preWhere);
            }
        }

        SQLExpr where = queryBlock.getWhere();
        if (where == null) {
            return;
        }

        println();
        print0(ucase ? "WHERE " : "where ");

        List<String> beforeComments = where.getBeforeCommentsDirect();
        if (beforeComments != null && !beforeComments.isEmpty() && isPrettyFormat()) {
            printlnComments(beforeComments);
        }
        printExpr(where, parameterized);
    }

    @Override
    protected void printFrom(SQLSelectQueryBlock x) {
        SQLTableSource from = x.getFrom();
        if (from == null) {
            return;
        }

        List<String> beforeComments = from.getBeforeCommentsDirect();
        if (beforeComments != null) {
            for (String comment : beforeComments) {
                println();
                print0(comment);
            }
        }

        super.printFrom(x);
        if (x instanceof CKSelectQueryBlock && ((CKSelectQueryBlock) x).isFinal()) {
            print0(ucase ? " FINAL" : " final");
        }
    }

    @Override
    protected void printGroupBy(SQLSelectQueryBlock x) {
        super.printGroupBy(x);
        if (x instanceof CKSelectQueryBlock && ((CKSelectQueryBlock) x).isWithTotals()) {
            print0(ucase ? " WITH TOTALS" : " with totals");
        }
    }

    @Override
    protected void printOrderBy(SQLSelectQueryBlock x) {
        super.printOrderBy(x);
        if (x instanceof CKSelectQueryBlock && ((CKSelectQueryBlock) x).isWithFill()) {
            print0(ucase ? " WITH FILL" : " with fill");
        }
    }

    @Override
    protected void printLimit(SQLSelectQueryBlock x) {
        super.printLimit(x);
        if (x instanceof CKSelectQueryBlock && ((CKSelectQueryBlock) x).isWithTies()) {
            print0(ucase ? " WITH TIES" : " with ties");
        }
    }

    @Override
    protected void printCreateTableAfterName(SQLCreateTableStatement x) {
        if (x instanceof CKCreateTableStatement) {
            CKCreateTableStatement ckStmt = (CKCreateTableStatement) x;
            if (!StringUtils.isEmpty(ckStmt.getOnClusterName())) {
                print0(ucase ? " ON CLUSTER " : " on cluster ");
                print(ckStmt.getOnClusterName());
            }
        }
    }

    @Override
    protected void printEngine(SQLCreateTableStatement x) {
        if (x instanceof CKCreateTableStatement) {
            SQLExpr engine = ((CKCreateTableStatement) x).getEngine();
            if (engine != null) {
                print0(ucase ? " ENGINE = " : " engine = ");
                engine.accept(this);
            }
        }
    }

    @Override
    public boolean visit(SQLMapDataType x) {
        print0(ucase ? "MAP(" : "map(");

        SQLDataType keyType = x.getKeyType();
        SQLDataType valueType = x.getValueType();

        keyType.accept(this);
        print0(", ");

        valueType.accept(this);
        print(')');
        return false;
    }

    @Override
    public boolean visit(SQLArrayDataType x) {
        print0(ucase ? "ARRAY(" : "array(");
        x.getComponentType().accept(this);
        print(')');
        return false;
    }
}
