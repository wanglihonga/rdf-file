package com.alipay.rdf.file.condition;

import java.util.ArrayList;
import java.util.List;

import com.alipay.rdf.file.exception.RdfErrorEnum;
import com.alipay.rdf.file.exception.RdfFileException;
import com.alipay.rdf.file.loader.ExtensionLoader;
import com.alipay.rdf.file.meta.FileBodyMeta;
import com.alipay.rdf.file.meta.FileColumnMeta;
import com.alipay.rdf.file.model.FileConfig;
import com.alipay.rdf.file.model.RowCondition;
import com.alipay.rdf.file.spi.RdfFileColumnTypeSpi;
import com.alipay.rdf.file.spi.RdfFileRowConditionSpi;
import com.alipay.rdf.file.util.BeanMapWrapper;
import com.alipay.rdf.file.util.RdfFileUtil;

/**
 * Copyright (C) 2013-2018 Ant Financial Services Group
 *
 * 基于字段值匹配的行条件计算器
 * 
 * match:bol=true|seq(0,4)=aaa|age=15
 * 
 * 决策值null  返回false
 * 
 * @author hongwei.quhw
 * @version $Id: ExpressionRowCondition.java, v 0.1 2018年10月11日 下午8:48:10 hongwei.quhw Exp $
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class MatchRowCondition implements RdfFileRowConditionSpi {
    private final String            STRING_TYPE_NAME = "string";
    private final List<MatchHolder> matches          = new ArrayList<MatchHolder>();

    @Override
    public boolean serialize(FileConfig config, BeanMapWrapper row) {
        for (MatchHolder match : matches) {
            Object value = row.getProperty(match.name);

            if (null == value) {
                return false;
            }

            if (match.subString) {
                value = ((String) value).substring(match.start, match.end);
            }

            if (value instanceof Comparable) {
                if (((Comparable) value).compareTo((Comparable) match.objValue) != 0) {
                    return false;
                }
            } else if (!RdfFileUtil.equals(value.toString(), match.objValue.toString())) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean deserialize(FileConfig fileConfig, String[] row) {
        for (MatchHolder match : matches) {
            String value = row[match.columMeta.getColIndex()];
            if (RdfFileUtil.isBlank(value)) {
                return false;
            }

            if (match.subString) {
                value = value.substring(match.start, match.end);
            }

            if (!RdfFileUtil.equals(value, match.strValue)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void init(RowCondition rowCondition) {
        String[] params = rowCondition.getConditionParam().split("\\|");

        for (String param : params) {
            MatchHolder matchHolder = new MatchHolder();
            String[] pair = param.split("=");
            if (pair.length != 2) {
                throw new RdfFileException("rdf-file#MatchRowCondition.init tempaltePath=["
                                           + rowCondition.getFileMeta().getTemplatePath()
                                           + "], bodyTemplateName=["
                                           + rowCondition.getBodyTemplateName() + "], condition参数["
                                           + rowCondition.getConditionParam() + "]配置格式错误",
                    RdfErrorEnum.TEMPLATE_ERROR);
            }

            try {
                String name = pair[0].trim();
                int start = name.indexOf("(");
                if (start > 0 && name.endsWith(")")) {
                    int split = name.indexOf(",");
                    matchHolder.name = name.substring(0, start);
                    matchHolder.start = Integer.parseInt(name.substring(start + 1, split));
                    matchHolder.end = Integer
                        .parseInt(name.substring(split + 1, name.length() - 1));
                    matchHolder.subString = true;
                } else {
                    matchHolder.name = pair[0].trim();
                }
                matchHolder.strValue = pair[1].trim();
            } catch (Exception e) {
                throw new RdfFileException("rdf-file#MatchRowCondition.init tempaltePath=["
                                           + rowCondition.getFileMeta().getTemplatePath()
                                           + "], bodyTemplateName=["
                                           + rowCondition.getBodyTemplateName() + "], condition参数["
                                           + rowCondition.getConditionParam() + "]配置格式错误",
                    e, RdfErrorEnum.TEMPLATE_ERROR);
            }

            FileColumnMeta column = getColumnMeta(rowCondition, matchHolder.name);
            RdfFileUtil.assertNotNull(
                column,
                "rdf-file#MatchRowCondition.init tempaltePath=["
                        + rowCondition.getFileMeta().getTemplatePath() + "], bodyTemplateName=["
                        + rowCondition.getBodyTemplateName() + "], condition参数["
                        + rowCondition.getConditionParam() + "], columnName=[" + matchHolder.name
                        + "] 字段没有定义",
                RdfErrorEnum.COLUMN_NOT_DEFINED);

            if (matchHolder.subString
                && !STRING_TYPE_NAME.equalsIgnoreCase(column.getType().getName())) {
                throw new RdfFileException("rdf-file#MatchRowCondition.init tempaltePath=["
                                           + rowCondition.getFileMeta().getTemplatePath()
                                           + "], bodyTemplateName=["
                                           + rowCondition.getBodyTemplateName() + "], condition参数["
                                           + rowCondition.getConditionParam() + "] columType=["
                                           + matchHolder.name + "]不是string类型不支持字符串截取",
                    RdfErrorEnum.TEMPLATE_ERROR);
            }

            matchHolder.columMeta = column;

            RdfFileColumnTypeSpi columnTypeCodec = ExtensionLoader
                .getExtensionLoader(RdfFileColumnTypeSpi.class)
                .getExtension(column.getType().getName());
            RdfFileUtil.assertNotNull(
                columnTypeCodec,
                "rdf-file#MatchRowCondition.init tempaltePath=["
                                 + rowCondition.getFileMeta().getTemplatePath()
                                 + "], bodyTemplateName=[" + rowCondition.getBodyTemplateName()
                                 + "],没有type=" + column.getType().getName() + " 对应的类型codec");
            matchHolder.objValue = columnTypeCodec.deserialize(matchHolder.strValue, column);

            matches.add(matchHolder);
        }
    }

    private FileColumnMeta getColumnMeta(RowCondition rowCondition, String colName) {
        if (RowConditionType.MULTI_BODY_TEMPLATE.equals(rowCondition.getType())) {
            return rowCondition.getFileMeta().getBodyMeta(rowCondition.getBodyTemplateName())
                .getColumn(colName);
        } else {
            FileColumnMeta colMeta = null;
            for (FileBodyMeta bodyMeta : rowCondition.getFileMeta().getBodyMetas()) {
                try {
                    colMeta = bodyMeta.getColumn(colName);
                } catch (RdfFileException e) {
                    if (RdfErrorEnum.COLUMN_NOT_DEFINED.equals(e.getErrorEnum())) {
                        continue;
                    } else {
                        throw e;
                    }
                }
                if (null != colMeta) {
                    break;
                }
            }
            return colMeta;
        }
    }

    private static final class MatchHolder {
        /**字段名*/
        private String         name;
        /**字段元数据*/
        private FileColumnMeta columMeta;
        /**配置的匹配值*/
        private String         strValue;
        /**转化的匹配值*/
        private Object         objValue;
        /**是否是截取字符串*/
        private boolean        subString;
        /**字符串截取开始位置*/
        private int            start;
        /**字符串截取结束位置*/
        private int            end;
    }
}
