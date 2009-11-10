package net.paoding.rose.jade.jadeinterface.provider.springjdbctemplte;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import net.paoding.rose.jade.jadeinterface.provider.DataAccess;
import net.paoding.rose.jade.jadeinterface.provider.Modifier;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlProvider;
import org.springframework.util.Assert;

/**
 * 通过 SpringJdbcTemplate 实现的 {@link DataAccess}.
 * 
 * @author han.liao
 */
public class SpringJdbcTemplateDataAccess implements DataAccess {

    // 创建  PreparedStatement 时指定  Statement.RETURN_GENERATED_KEYS 属性
    private static class GenerateKeysPreparedStatementCreator implements PreparedStatementCreator,
            SqlProvider {

        private final String sql;

        public GenerateKeysPreparedStatementCreator(String sql) {
            Assert.notNull(sql, "SQL must not be null");
            this.sql = sql;
        }

        public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
            return con.prepareStatement(this.sql, Statement.RETURN_GENERATED_KEYS);
        }

        public String getSql() {
            return this.sql;
        }
    }

    private static final Pattern PATTERN = Pattern.compile("\\:([a-zA-Z0-9_\\.]*)");

    private JdbcTemplate jdbcTemplate = new JdbcTemplate();

    public SpringJdbcTemplateDataAccess(DataSource dataSource) {
        jdbcTemplate.setDataSource(dataSource);
    }

    @Override
    public List<?> select(String sql, Modifier modifier, Map<String, ?> parameters,
            RowMapper rowMapper) {

        if (sql == null) {
            throw new IllegalArgumentException("SQL must not be null");
        }

        // 用 :name 参数的值填充列表
        final List<Object> values = new ArrayList<Object>();

        sql = resolveParam(sql, parameters, values);

        return select(sql, values.toArray(), rowMapper);
    }

    @Override
    public int update(String sql, Modifier modifier, Map<String, ?> parameters) {

        if (sql == null) {
            throw new IllegalArgumentException("SQL must not be null");
        }

        // 用 :name 参数的值填充列表
        final List<Object> values = new ArrayList<Object>();

        sql = resolveParam(sql, parameters, values);

        return update(sql, values.toArray());
    }

    @Override
    public Number insertReturnId(String sql, Modifier modifier, Map<String, ?> parameters) {

        if (sql == null) {
            throw new IllegalArgumentException("SQL must not be null");
        }

        // 用 :name 参数的值填充列表
        final List<Object> values = new ArrayList<Object>();

        sql = resolveParam(sql, parameters, values);

        return insertReturnId(sql, values.toArray());
    }

    /**
     * 执行 SELECT 语句。
     * 
     * @param sql - 执行的语句
     * @param parameters - 参数
     * @param rowMapper - 对象映射方式
     * 
     * @return 返回的对象列表
     */
    protected List<?> select(String sql, Object[] parameters, RowMapper rowMapper) {

        if (parameters.length > 0) {

            return jdbcTemplate.query(sql, parameters, rowMapper);

        } else {

            return jdbcTemplate.query(sql, rowMapper);
        }
    }

    /**
     * 执行 UPDATE / DELETE 语句。
     * 
     * @param sql - 执行的语句
     * @param parameters - 参数
     * 
     * @return 更新的记录数目
     */
    protected int update(String sql, Object[] parameters) {

        if (parameters.length > 0) {

            return jdbcTemplate.update(sql, parameters);

        } else {

            return jdbcTemplate.update(sql);
        }
    }

    /**
     * 执行 INSERT 语句，并返回插入对象的 ID.
     * 
     * @param sql - 执行的语句
     * @param parameters - 参数
     * 
     * @return 插入对象的 ID
     */
    protected Number insertReturnId(String sql, Object[] parameters) {

        if (parameters.length > 0) {

            PreparedStatementCallbackReturnId callbackReturnId = new PreparedStatementCallbackReturnId(
                    new ArgPreparedStatementSetter(parameters));

            return (Number) jdbcTemplate.execute(new GenerateKeysPreparedStatementCreator(sql),
                    callbackReturnId);

        } else {

            PreparedStatementCallbackReturnId callbackReturnId = new PreparedStatementCallbackReturnId();

            return (Number) jdbcTemplate.execute(new GenerateKeysPreparedStatementCreator(sql),
                    callbackReturnId);
        }
    }

    /**
     * 查找 SQL 语句中所有的 :name, :name.property 参数, 将值写入列表, 并且返回包含 '?' 的 SQL 语句。
     * 
     * @param sql - SQL 语句
     * @param parameters - [in] 传入的参数
     * @param values - [out] 填充的参数列表
     * 
     * @return 包含 '?' 的 SQL 语句
     */
    private String resolveParam(String sql, Map<String, ?> parameters, final List<Object> values) {

        // 匹配符合  :name 格式的参数
        Matcher matcher = PATTERN.matcher(sql);
        if (matcher.find()) {

            StringBuilder builder = new StringBuilder();

            int index = 0;

            do {
                // 提取参数名称
                final String name = matcher.group(1).trim();

                Object value = null;

                // 解析  a.b.c 类型的名称 
                int find = name.indexOf('.');
                if (find >= 0) {

                    // 用  BeanWrapper 获取属性值
                    Object bean = parameters.get(name.substring(0, find));
                    if (bean != null) {
                        BeanWrapper beanWrapper = new BeanWrapperImpl(bean);
                        value = beanWrapper.getPropertyValue(name.substring(find + 1));
                    }

                } else {
                    // 直接获取值
                    value = parameters.get(name);
                }

                // 拼装查询语句
                builder.append(sql.substring(index, matcher.start()));

                if (value instanceof Collection<?>) {

                    // 拼装 IN (...) 的查询条件
                    builder.append('(');

                    Collection<?> collection = (Collection<?>) value;

                    if (collection.isEmpty()) {
                        builder.append("NULL");
                    } else {
                        builder.append('?');
                    }

                    for (int i = 1; i < collection.size(); i++) {
                        builder.append(", ?");
                    }

                    builder.append(')');

                    // 保存参数值
                    values.addAll(collection);

                } else {
                    // 拼装普通的查询条件
                    builder.append('?');

                    // 保存参数值
                    values.add(value);
                }

                index = matcher.end();

            } while (matcher.find());

            // 拼装查询语句
            builder.append(sql.substring(index));

            return builder.toString();
        }

        return sql;
    }
}