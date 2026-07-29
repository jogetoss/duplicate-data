package org.joget.plugin.enterprise;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormBinder;
import org.joget.apps.form.model.FormBuilderPalette;
import org.joget.apps.form.model.FormBuilderPaletteElement;
import org.joget.apps.form.model.FormContainer;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormLoadBinder;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.model.GridInnerDataRetriever;
import org.joget.apps.form.service.FileUtil;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.FileManager;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.UuidGenerator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DuplicateData extends Element implements FormBuilderPaletteElement, FormLoadBinder, FormContainer {

    protected boolean isRetrieveData = false;
    protected String uuid = null;

    @Override
    public String getName() {
        return "DuplicateData";
    }

    @Override
    public String getVersion() {
        return "6.0.8";
    }

    @Override
    public String getDescription() {
        return "Duplicate Data";
    }

    @Override
    public String renderTemplate(FormData formData, Map dataModel) {
        String html = FormUtil.generateElementHtml(this, formData, "duplicateData.ftl", dataModel);
        return html;
    }

    @Override
    public FormRowSet formatData(FormData formData) {
        return null;
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getFormBuilderTemplate() {
        return "<span class='form-floating-label'>Duplicate Data</span>";
    }

    @Override
    public String getLabel() {
        return "Duplicate Data";
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(), "/properties/form/duplicateData.json", null, true, null);
    }

    @Override
    public String getFormBuilderCategory() {
        return FormBuilderPalette.CATEGORY_CUSTOM;
    }

    @Override
    public int getFormBuilderPosition() {
        return 100;
    }

    @Override
    public String getFormBuilderIcon() {
        return "/plugin/org.joget.apps.form.lib.TextField/images/textField_icon.gif";
    }

    @Override
    public FormLoadBinder getLoadBinder() {
        if (isRetrieveData) {
            return null;
        } else {
            return this;
        }
    }

    public FormRowSet load(Element element, String primaryKey, FormData formData) {
        String id = formData.getRequestParameter(getPropertyString("paramname"));
        if (!isRetrieveData && id != null && !id.isEmpty() && !FormUtil.isFormSubmitted(element, formData)) {
            if (uuid == null) {
                uuid = UuidGenerator.getInstance().getUuid();
            }
            isRetrieveData = true;
            boolean enableMultipageDataCopy = "true".equals(getPropertyString("CopyMultipageData"));

            try {
                if (enableMultipageDataCopy) {
                    AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
                    String formId = FormUtil.findRootForm(element).getPropertyString("id");
                    String tableName = appService.getFormTableName(AppUtil.getCurrentAppDefinition(), formId);

                    JSONObject mpfProps = getMultiPagedProps(formId);
                    Map<String, String> formTableMap
                            = (mpfProps == null) ? Collections.emptyMap()
                                    : extractFormDefIdTableMap(mpfProps);

                    String newId = duplicateRow(formId, tableName, id, formTableMap);
                    if (newId == null) {
                        LogUtil.warn(getClassName(), "Failed to duplicate record for ID: " + id);
                        return null;
                    }

                    // Set new primary key to formData
                    formData.setPrimaryKeyValue(newId);
                    formData.addRequestParameterValues("_duplicateData_done", new String[]{"true"});

                    FormData newFormData = new FormData();
                    newFormData.setPrimaryKeyValue(newId);
                    Form rootForm = FormUtil.findRootForm(element);
                    FormUtil.executeLoadBinders(rootForm, newFormData);
                    setFormData(rootForm, formData, newFormData);
                } else {
                    FormData newFormData = new FormData();
                    newFormData.setPrimaryKeyValue(id);
                    Form rootForm = FormUtil.findRootForm(element);
                    FormUtil.executeLoadBinders(rootForm, newFormData);

                    setFormData(rootForm, formData, newFormData);

                }
            } catch (Exception e) {
                if (enableMultipageDataCopy) {
                    LogUtil.error(getClassName(), e, "Error duplicating data from source ID: " + id);
                } else {
                    LogUtil.error(getClassName(), e, "Error loading data from source ID: " + id);
                }
            } finally {
                isRetrieveData = false;
            }
        }
        return null;
    }

    
    private Map<String, String> extractFormDefIdTableMap(JSONObject mpfProps) {
        JSONObject numberOfPage = mpfProps.optJSONObject("numberOfPage");
        if (numberOfPage == null) {
            LogUtil.warn("MultiPagedExtractor", "No numberOfPage property in MultiPagedForm");
            return Collections.emptyMap();
        }

        int pageCount = 0;
        try {
            pageCount = Integer.parseInt(numberOfPage.optString("className", "0"));
        } catch (NumberFormatException ignore) {
        }

        JSONObject pageProps = numberOfPage.optJSONObject("properties");
        if (pageProps == null) {
            return Collections.emptyMap();
        }

        Map<String, String> formTableMap = new LinkedHashMap<>(); 

        for (int i = 1; i <= pageCount; i++) {
            String key1 = "page" + i + "_formDefId";
            String key2 = "page" + i + "_formdefid";
            String formDefId = pageProps.has(key1) ? pageProps.optString(key1, null) : pageProps.optString(key2, null);
            if (formDefId == null) {
                continue;
            }

            AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
            String tableName = appService.getFormTableName(AppUtil.getCurrentAppDefinition(), formDefId);

            formTableMap.put(formDefId, tableName);
        }

        return formTableMap;
    }

 
    public Map<String, String> detectForeignKeys(FormRow source, Map<String, String> formDefIdTableMap) {
        FormDataDao dao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
        Map<String, String> matches = new HashMap<>();

        for (Map.Entry<Object, Object> e : (Set<Map.Entry<Object, Object>>) source.entrySet()) {
            String columnName = String.valueOf(e.getKey());
            String value = String.valueOf(e.getValue());

            for (Map.Entry<String, String> tableEntry : formDefIdTableMap.entrySet()) {
                String formDefId = tableEntry.getKey();
                String tableName = tableEntry.getValue();

                FormRow row = dao.load(formDefId, tableName, value);
                if (row != null) {
                    matches.put(columnName, tableName);
                    break;                
                }
            }
        }
        return matches;
    }

    protected String duplicateRow(String formId,
            String tableName,
            String sourceId,
            Map<String, String> formTableMap) {
        FormDataDao dao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
        FormRow source = dao.load(formId, tableName, sourceId);
        if (source == null) {
            return null;
        }

        if (formTableMap == null || formTableMap.isEmpty()) {
            LogUtil.warn(getClassName(), "No form-to-table mapping - aborting duplicateRow");
            return null;
        }
        Map<String, String> tableMap = formTableMap;

        Map<String, String> fkMatches = detectForeignKeys(source, tableMap);

        String newId = UuidGenerator.getInstance().getUuid();
        FormRow clone = new FormRow();
        clone.putAll(source);
        clone.setId(newId);
        clone.remove("dateCreated");
        clone.remove("dateModified");
        clone.remove("createdBy");
        clone.remove("modifiedBy");

        for (Map.Entry<String, String> entry : fkMatches.entrySet()) {
            String column = entry.getKey();        
            String tableNameMatched = entry.getValue(); 
            String fkValue = String.valueOf(source.get(column)); 

            String matchedFormDefId = null;
            for (Map.Entry<String, String> t : tableMap.entrySet()) {
                if (t.getValue().equals(tableNameMatched)) {
                    matchedFormDefId = t.getKey();
                    break;
                }
            }

            if (matchedFormDefId != null) {
                FormRow referenced = dao.load(matchedFormDefId, tableNameMatched, fkValue);
                if (referenced != null) {
                    String newFkId = UuidGenerator.getInstance().getUuid();
                    FormRow refClone = new FormRow();
                    refClone.putAll(referenced);
                    refClone.setId(newFkId);
                    refClone.remove("dateCreated");
                    refClone.remove("dateModified");
                    refClone.remove("createdBy");
                    refClone.remove("modifiedBy");

                    FormRowSet refSet = new FormRowSet();
                    refSet.setMultiRow(false);
                    refSet.add(refClone);
                    dao.saveOrUpdate(matchedFormDefId, tableNameMatched, refSet);
                    copyUploadedFiles(tableNameMatched, fkValue, newFkId);
                    clone.setProperty(column, newFkId);

                    
                }
            }
        }

        FormRowSet rs = new FormRowSet();
        rs.setMultiRow(false);
        rs.add(clone);
        dao.saveOrUpdate(formId, tableName, rs);


        return newId;
    }

    protected void copyUploadedFiles(String tableName, String oldPk, String newPk) {
        try {
            String oldPath = FileUtil.getUploadPath(tableName, oldPk);
            File oldDir = new File(oldPath);
            if (oldDir.exists()) {
                String newPath = FileUtil.getUploadPath(tableName, newPk);
                File newDir = new File(newPath);
                if (!newDir.exists()) {
                    newDir.mkdirs();
                }
                FileUtils.copyDirectory(oldDir, newDir);
               
            }
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, "Error copying uploaded files from "
                    + oldPk + " to " + newPk + " in table " + tableName);
        }
    }

    private JSONObject findMultiPagedProps(JSONObject element, String targetId) {
        if (element == null) {
            return null;
        }

        if ("org.joget.plugin.enterprise.MultiPagedForm"
                .equals(element.optString("className"))) {
            JSONObject props = element.optJSONObject("properties");
            if (props != null && targetId.equals(props.optString("id"))) {
                return props;          
            }
        }

        JSONArray children = element.optJSONArray("elements");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject hit = findMultiPagedProps(children.optJSONObject(i), targetId);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }


    private JSONObject getMultiPagedProps(String formDefinitionId) throws JSONException {
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        FormDefinitionDao dao = (FormDefinitionDao) AppUtil.getApplicationContext()
                .getBean("formDefinitionDao");
        FormDefinition formDef = dao.loadById(formDefinitionId, appDef);
        if (formDef == null || formDef.getJson() == null) {
            return null;
        }

        JSONObject root = new JSONObject(formDef.getJson());

        JSONObject mpf = findMultiPagedElement(root);
        if (mpf == null) {
            return null;
        }

        String dynamicId = mpf.optJSONObject("properties")
                .optString("id", null);
        if (dynamicId == null) {
            return null;
        }

        return findMultiPagedProps(root, dynamicId);
    }

    private JSONObject findMultiPagedElement(JSONObject element) {
        if (element == null) {
            return null;
        }

        if ("org.joget.plugin.enterprise.MultiPagedForm"
                .equals(element.optString("className"))) {
            return element;                 
        }

        JSONArray arr = element.optJSONArray("elements");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject hit = findMultiPagedElement(arr.optJSONObject(i));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    protected void setFormData(Element element, FormData formData, FormData newFormData) throws IOException {

        if (element.getLoadBinder() != null) {
            processRows(element, formData, newFormData);
        }

        for (Element child : element.getChildren()) {
            setFormData(child, formData, newFormData);
        }
    }

    protected JSONObject convertFormDataToRequestParam(Element element, Form innerForm, FormRow row, FormData formData, FormData innerFormData, int index) throws JSONException, IOException {
        for (Element e : innerForm.getChildren()) {
            FormUtil.executeLoadBinders(e, innerFormData);
        }
        recursiveSetRequestParam(innerForm, innerFormData, false);

        JSONObject obj = new JSONObject();
        JSONObject tempRP = new JSONObject();
        for (String key : (Set<String>) row.getCustomProperties().keySet()) {
            if (!key.equals(FormUtil.PROPERTY_ID)) {
                Object value = row.get(key);
                obj.put(key, value);
                tempRP.put(key, new String[]{value != null ? value.toString() : ""});
            }
        }

        if (innerFormData.getRequestParams().size() > 0) {
            for (String key : innerFormData.getRequestParams().keySet()) {
                tempRP.put(key, innerFormData.getRequestParameterValues(key));
            }
        }
        if (row.getTempFilePathMap() != null && !row.getTempFilePathMap().isEmpty()) {
            JSONObject temp = new JSONObject();
            for (String key : row.getTempFilePathMap().keySet()) {
                temp.put(key, row.getTempFilePaths(key));
            }
            obj.put("_tempFilePathMap", temp);
        }

        tempRP.put(
                FormUtil.getElementParameterName(innerForm) + "_SUBMITTED",
                new String[]{"true"}
        );

        obj.put("_tempRequestParamsMap", tempRP);
        String paramName = FormUtil.getElementParameterName(element) + "_jsonrow_" + index;
        formData.addRequestParameterValues(paramName, new String[]{obj.toString()});

        return obj;
    }

    protected void recursiveSetRequestParam(Element element, FormData formData, boolean set) throws JSONException, IOException {
        if (element.getLoadBinder() != null) {
            set = true;
            processRows(element, formData, formData);
        } else if (set) {
            String[] values = FormUtil.getElementPropertyValues(element, formData);
            formData.addRequestParameterValues(FormUtil.getElementParameterName(element), values);
        }

        for (Element e : element.getChildren()) {
            recursiveSetRequestParam(e, formData, set);
        }
    }

    protected void processRows(Element element, FormData formData, FormData newformData) throws IOException {

        FormRowSet rows = newformData.getLoadBinderData(element);
        if (rows == null) {
            return;
        }

        JSONArray spreadsheetJsonArray = new JSONArray();
        int rc = 0;

        for (FormRow row : rows) {
            //copy uploaded files to temp folder
            String pk = row.getId();
            if (pk != null && !pk.isEmpty()) {

                handleFiles(element, pk, row);

                
                if (element instanceof GridInnerDataRetriever) {
                    try {
                        Form innerForm = ((GridInnerDataRetriever) element).getInnerForm();
                        if (innerForm != null) {
                            FormData innerFormData = new FormData();
                            innerFormData.setPrimaryKeyValue(pk);
                            convertFormDataToRequestParam(element, innerForm, row, formData, innerFormData, rc);
                        }
                    } catch (Exception e) {
                        LogUtil.error(getClassName(), e, "Error injecting jsonrow");
                    }
                }

                row.setId(null);
            }

            if (element.getName().equalsIgnoreCase("SpreadSheet")) {
                JSONObject obj = new JSONObject();
                try {
                    for (String key : (Set<String>) row.getCustomProperties().keySet()) {
                        if (!key.equals(FormUtil.PROPERTY_ID)) {
                            Object v = row.get(key);
                            obj.put(key, (v != null) ? v : JSONObject.NULL);
                        }
                    }
                } catch (JSONException e) {
                    LogUtil.error(getClassName(), e, "Error building spreadsheet JSON");
                }
                spreadsheetJsonArray.put(obj);
            }
            rc++;
        }

        if (element.getName().equalsIgnoreCase("SpreadSheet")) {
            String spreadsheetParam = FormUtil.getElementParameterName(element) + "_JSON_DATA";
            formData.addRequestParameterValues(spreadsheetParam, new String[]{spreadsheetJsonArray.toString()});
        }

        if (element instanceof GridInnerDataRetriever) {

            formData.getLoadBinderMap().remove(element.getLoadBinder());
        } else if (element.getLoadBinder() != null) {
            formData.setLoadBinderData(element.getLoadBinder(), rows);
        }
    }

    protected void handleFiles(Element element, String pk, FormRow row) throws IOException {
        String path = FileUtil.getUploadPath(element, pk);
        if (!((FormBinder) element.getLoadBinder()).getPropertyString("formDefId").isEmpty()) {
            AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
            String tableName = appService.getFormTableName(AppUtil.getCurrentAppDefinition(), ((FormBinder) element.getLoadBinder()).getPropertyString("formDefId"));
            path = FileUtil.getUploadPath(tableName, pk);
        }
        File dir = new File(path);
        if (dir.exists()) {
            String tempPath = FileManager.getBaseDirectory() + uuid + "_" + pk;
            File newDir = new File(tempPath);
            if (!newDir.exists()) {
                newDir.mkdirs();
                FileUtils.copyDirectory(dir, newDir);
            }

            //loop all value and replace filename to tempath
            Set<String> files = new HashSet<String>();
            File[] listOfFiles = dir.listFiles();
            for (File f : listOfFiles) {
                if (f.isFile()) {
                    files.add(f.getName());
                }
            }

            boolean isFile;
            for (String k : (Set<String>) row.getCustomProperties().keySet()) {
                Set<String> temPaths = new HashSet<String>();
                String[] values = row.getProperty(k).split(";");
                isFile = false;
                for (int i = 0; i < values.length; i++) {
                    if (files.contains(values[i])) {
                        String filename = values[i];
                        if (!(element instanceof GridInnerDataRetriever)) {
                            values[i] = uuid + "_" + pk + File.separator + filename;
                        }
                        temPaths.add(uuid + "_" + pk + File.separator + filename);
                        if (files.contains(filename + ".thumb.jpg")) {
                            temPaths.add(uuid + "_" + pk + File.separator + filename + ".thumb.jpg");
                        }
                        isFile = true;
                    }
                }
                if (isFile) {
                    row.setProperty(k, String.join(";", values));
                    row.putTempFilePath(k, temPaths.toArray(new String[0]));
                }
            }
        }
    }
}
