/*
 * Copyright (c) 2010 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.

 * Author: Vasiliy Fontanenko
 * Created: 12.10.2010 19:21:36
 *
 * $Id: DocFormatter.java 10587 2013-02-19 08:40:16Z degtyarjov $
 */
package com.haulmont.newreport.formatters.impl;

import com.haulmont.newreport.structure.impl.Band;
import com.haulmont.newreport.structure.ReportOutputType;
import com.haulmont.newreport.structure.impl.ReportValueFormat;
import com.haulmont.newreport.exception.ReportingException;
import com.haulmont.newreport.formatters.impl.doc.ClipBoardHelper;
import com.haulmont.newreport.formatters.impl.doc.ODTTableHelper;
import com.haulmont.newreport.formatters.impl.doc.OOOutputStream;
import com.haulmont.newreport.formatters.impl.doc.OfficeComponent;
import com.haulmont.newreport.formatters.impl.doc.connector.NoFreePortsException;
import com.haulmont.newreport.formatters.impl.doc.connector.OOConnection;
import com.haulmont.newreport.formatters.impl.doc.connector.OOConnectorAPI;
import com.haulmont.newreport.formatters.impl.tags.TagHandler;
import com.haulmont.newreport.structure.ReportTemplate;
import com.sun.star.container.NoSuchElementException;
import com.sun.star.container.XIndexAccess;
import com.sun.star.frame.XComponentLoader;
import com.sun.star.frame.XDispatchHelper;
import com.sun.star.io.IOException;
import com.sun.star.io.XInputStream;
import com.sun.star.lang.WrappedTargetException;
import com.sun.star.lang.XComponent;
import com.sun.star.table.XCell;
import com.sun.star.text.*;
import com.sun.star.util.XReplaceable;
import com.sun.star.util.XSearchDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static com.haulmont.newreport.formatters.impl.doc.ODTHelper.*;
import static com.haulmont.newreport.formatters.impl.doc.ODTTableHelper.*;
import static com.haulmont.newreport.formatters.impl.doc.ODTUnoConverter.*;

/**
 * Document formatter for '.doc' file types
 */
public class DocFormatter extends AbstractFormatter {
    protected static final Logger log = LoggerFactory.getLogger(DocFormatter.class);

    protected static final String SEARCH_REGULAR_EXPRESSION = "SearchRegularExpression";

    protected static final String PDF_OUTPUT_FILE = "writer_pdf_Export";
    protected static final String MS_WORD_OUTPUT_FILE = "MS Word 97";

    /**
     * Chain of responsibility for tags
     */
    protected List<TagHandler> tagHandlers = new ArrayList<TagHandler>();

    protected OOConnection connection;

    protected XComponent xComponent;

    protected OfficeComponent officeComponent;

    protected OOConnectorAPI connectorAPI;

    public DocFormatter(Band rootBand, ReportTemplate reportTemplate, OutputStream outputStream, OOConnectorAPI connectorAPI) {
        super(rootBand, reportTemplate, outputStream);
        this.connectorAPI = connectorAPI;
    }

    public void renderDocument() {
        if (reportTemplate == null)
            throw new NullPointerException("Template file can't be null");

        try {
            doCreateDocument(reportTemplate.getOutputType(), outputStream);
        } catch (Exception e) {//just try again if any exceptions occurred
            log.warn("An error occured while generating doc report. System will retry to generate report once again.", e);
            try {
                doCreateDocument(reportTemplate.getOutputType(), outputStream);
            } catch (NoFreePortsException e1) {
                //todo handle
            }
        }
    }

    private void doCreateDocument(final ReportOutputType outputType, final OutputStream outputStream) throws NoFreePortsException {
        createOpenOfficeConnection();

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    XInputStream xis = getXInputStream(reportTemplate);
                    XComponentLoader xComponentLoader = connection.createXComponentLoader();
                    xComponent = loadXComponent(xComponentLoader, xis);

                    officeComponent = new OfficeComponent(connection, xComponentLoader, xComponent);

                    // Lock clipboard
                    synchronized (ClipBoardHelper.class) {
                        // Handling tables
                        fillTables();
                    }
                    // Handling text
                    replaceAllAliasesInDocument();
                    // Saving document to output stream and closing
                    saveAndClose(xComponent, outputType, outputStream);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        connectorAPI.openConnectionRunWithTimeoutAndClose(connection, runnable, 60);//todo parameters
    }

    private void createOpenOfficeConnection() throws NoFreePortsException {
        connection = connectorAPI.createConnection();
    }

    private void saveAndClose(XComponent xComponent, ReportOutputType outputType, OutputStream outputStream)
            throws IOException {
        OOOutputStream ooos = new OOOutputStream(outputStream);
        String filterName;
        if (ReportOutputType.pdf.equals(outputType)) {
            filterName = PDF_OUTPUT_FILE;
        } else {
            filterName = MS_WORD_OUTPUT_FILE;
        }
        saveXComponent(xComponent, ooos, filterName);
        closeXComponent(xComponent);
    }

    //todo allow to define table name as docx formatter does (##band=Band1 in first cell)
    private void fillTables() throws com.sun.star.uno.Exception {
        List<String> tablesNames = getTablesNames(xComponent);
        tablesNames.retainAll(rootBand.getBandDefinitionNames());

        XDispatchHelper xDispatchHelper = connection.createXDispatchHelper();
        for (String tableName : tablesNames) {
            Band band = rootBand.findBandRecursively(tableName);
            XTextTable xTextTable = getTableByName(xComponent, tableName);
            if (band != null) {
                // todo remove this hack!
                // try to select one cell without it workaround
                int columnCount = xTextTable.getColumns().getCount();
                if (columnCount < 2)
                    xTextTable.getColumns().insertByIndex(columnCount, 1);
                fillTable(tableName, band.getParentBand(), xTextTable, xDispatchHelper);
                // end of workaround ->
                if (columnCount < 2)
                    xTextTable.getColumns().removeByIndex(columnCount, 1);
            } else {
                if (haveValueExpressions(xTextTable))
                    deleteLastRow(xTextTable);
            }
        }
    }

    public boolean haveValueExpressions(XTextTable xTextTable) {
        int lastrow = xTextTable.getRows().getCount() - 1;
        try {
            for (int i = 0; i < xTextTable.getColumns().getCount(); i++) {
                String templateText = asXText(ODTTableHelper.getXCell(xTextTable, i, lastrow)).getString();
                if (UNIVERSAL_ALIAS_PATTERN.matcher(templateText).find()) {
                    return true;
                }
            }
        } catch (Exception e) {
            throw new ReportingException(e);
        }
        return false;
    }

    private void fillTable(String name, Band parentBand, XTextTable xTextTable, XDispatchHelper xDispatchHelper)
            throws com.sun.star.uno.Exception {
        boolean displayDeviceUnavailable = true;//todo parameter
        if (!displayDeviceUnavailable) {
            ClipBoardHelper.clear();
        }
        int startRow = xTextTable.getRows().getCount() - 1;
        List<Band> childrenBands = parentBand.getChildrenList();
        for (Band child : childrenBands) {
            if (name.equals(child.getName())) {
                duplicateLastRow(xDispatchHelper, asXTextDocument(xComponent).getCurrentController(), xTextTable);
            }
        }
        int i = startRow;
        for (Band child : childrenBands) {
            if (name.equals(child.getName())) {
                fillRow(child, xTextTable, i);
                i++;
            }
        }
        deleteLastRow(xTextTable);
    }

    private void fillRow(Band band, XTextTable xTextTable, int row)
            throws com.sun.star.lang.IndexOutOfBoundsException, NoSuchElementException, WrappedTargetException {
        int colCount = xTextTable.getColumns().getCount();
        for (int col = 0; col < colCount; col++) {
            fillCell(band, getXCell(xTextTable, col, row));
        }
    }

    private void fillCell(Band band, XCell xCell) throws NoSuchElementException, WrappedTargetException {
        String cellText = preformatCellText(asXText(xCell).getString());
        List<String> parametersToInsert = new ArrayList<String>();
        Matcher matcher = UNIVERSAL_ALIAS_PATTERN.matcher(cellText);
        while (matcher.find()) {
            parametersToInsert.add(unwrapParameterName(matcher.group()));
        }
        for (String parameterName : parametersToInsert) {
            XText xText = asXText(xCell);
            XTextCursor xTextCursor = xText.createTextCursor();

            String paramStr = "${" + parameterName + "}";
            int index = cellText.indexOf(paramStr);
            while (index >= 0) {
                xTextCursor.gotoStart(false);
                xTextCursor.goRight((short) (index + paramStr.length()), false);
                xTextCursor.goLeft((short) paramStr.length(), true);

                insertValue(xText, xTextCursor, band, parameterName);
                cellText = preformatCellText(xText.getString());

                index = cellText.indexOf(paramStr);
            }
        }
    }

    /**
     * Replaces all aliases ${bandname.paramname} in document text.
     *
     * @throws com.haulmont.newreport.exception.ReportingException
     *          If there is not appropriate band or alias is bad
     */
    private void replaceAllAliasesInDocument() {
        XTextDocument xTextDocument = asXTextDocument(xComponent);
        XReplaceable xReplaceable = asXReplaceable(xTextDocument);
        XSearchDescriptor searchDescriptor = xReplaceable.createSearchDescriptor();
        searchDescriptor.setSearchString(ALIAS_WITH_BAND_NAME_REGEXP);
        try {
            searchDescriptor.setPropertyValue(SEARCH_REGULAR_EXPRESSION, true);
            XIndexAccess indexAccess = xReplaceable.findAll(searchDescriptor);
            for (int i = 0; i < indexAccess.getCount(); i++) {
                XTextRange textRange = asXTextRange(indexAccess.getByIndex(i));
                String alias = unwrapParameterName(textRange.getString());

                BandPathAndParameterName bandAndParameter = separateBandNameAndParameterName(alias);

                Band band = findBandByPath(rootBand, bandAndParameter.bandPath);

                if (band == null)
                    throw new ReportingException("No band for alias : " + alias);

                insertValue(textRange.getText(), textRange, band, bandAndParameter.parameterName);
            }
        } catch (Exception ex) {
            throw new ReportingException(ex);
        }
    }

    private void insertValue(XText text, XTextRange textRange, Band band, String paramName) {
        String fullParamName = band.getFullName() + "." + paramName;
        Object paramValue = band.getParameterValue(paramName);

        Map<String, ReportValueFormat> formats = rootBand.getValuesFormats();
        try {
            boolean handled = false;

            if (paramValue != null) {
                if ((formats != null) && (formats.containsKey(fullParamName))) {
                    String format = formats.get(fullParamName).getFormatString();
                    // Handle doctags
                    for (TagHandler tagHandler : tagHandlers) {
                        Matcher matcher = tagHandler.getTagPattern().matcher(format);
                        if (matcher.find()) {
                            tagHandler.handleTag(officeComponent, text, textRange, paramValue, matcher);
                            handled = true;
                        }
                    }
                }
                if (!handled) {
                    String valueString = formatValue(paramValue, fullParamName);
                    text.insertString(textRange, valueString, true);
                }
            } else
                text.insertString(textRange, "", true);
        } catch (Exception ex) {
            throw new ReportingException("Insert data error");
        }
    }
}