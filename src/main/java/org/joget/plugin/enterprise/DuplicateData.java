package org.joget.plugin.enterprise;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
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
import static org.joget.apps.form.service.FormUtil.findRootForm;
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
        return "6.0.1";
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
            try {
                FormData newFormData = new FormData();
                newFormData.setPrimaryKeyValue(id);
                Form rootForm = FormUtil.findRootForm(element);
                FormUtil.executeLoadBinders(rootForm, newFormData);
                
                setFormData(rootForm, formData, newFormData);
                
            } catch (Exception e) {
                LogUtil.error(getClassName(), e, "");
            } finally {
                isRetrieveData = false;
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
        String paramName = FormUtil.getElementParameterName(element) + "_jsonrow_" + index;
        
        //Skip parent
        for (Element e : innerForm.getChildren()) {
            FormUtil.executeLoadBinders(e, innerFormData);
        }
        
        JSONObject obj = new JSONObject();
        JSONObject tempRP = new JSONObject();
        for (String key : (Set<String>) row.getCustomProperties().keySet()) {
            if (!key.equals(FormUtil.PROPERTY_ID)) {
                obj.put(key, row.get(key));
                tempRP.put(key, row.get(key).toString().split(";"));
            }
        }
        
        if (innerFormData.getLoadBinderMap().size() > 0) {
            //Skip parent
            for (Element e : innerForm.getChildren()) {
                recursiveSetRequestParam(e, innerFormData, false);
            }
            
            if (innerFormData.getRequestParams().size() > 0) {
                for (String key : innerFormData.getRequestParams().keySet()) {
                    tempRP.put(key, innerFormData.getRequestParameterValues(key));
                }
            }
        }
        
        tempRP.put(FormUtil.getElementParameterName(innerForm) + "_SUBMITTED", new String[]{"true"});
        obj.put("_tempRequestParamsMap", tempRP);
        
        if (row.getTempFilePathMap() != null && row.getTempFilePathMap().size() > 0) {
            JSONObject temp = new JSONObject();
            for (String key : row.getTempFilePathMap().keySet()) {
                temp.put(key, row.getTempFilePaths(key));
            }
            obj.put("_tempFilePathMap", temp);
        }
        
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
        JSONArray jsonArray = new JSONArray();
        JSONObject obj = null;
        if (rows != null) {
            int rc = 0;
            for (FormRow row : rows) {
                //copy uploaded files to temp folder
                String pk = row.getId();
                if (pk != null) {
                    handleFiles(element, pk, row);

                    if (element instanceof GridInnerDataRetriever) {
                        Form innerForm = ((GridInnerDataRetriever) element).getInnerForm();
                        try {
                            FormData innerFormData = new FormData();
                            innerFormData.setPrimaryKeyValue(row.getId());

                            obj = convertFormDataToRequestParam(element, innerForm, row, formData, innerFormData, rc);
                            jsonArray.put(obj);
                        } catch (Exception ge) {
                            LogUtil.error(getClassName(), ge, "");
                        }
                    }

                    row.setId(null);
                }
                rc++;
            }
            
            if (element.getName().equalsIgnoreCase("SpreadSheet")) {
                String spreadsheetParam = FormUtil.getElementParameterName(element) + "_JSON_DATA";
                formData.addRequestParameterValues(spreadsheetParam, new String[]{jsonArray.toString()});
            }
            
            if (!(element instanceof GridInnerDataRetriever)) {
                formData.setLoadBinderData(element.getLoadBinder(), rows);
            } else {
                formData.getLoadBinderMap().remove(element.getLoadBinder());
                Form form = findRootForm(element);
                formData.addRequestParameterValues(FormUtil.getElementParameterName(form) + "_SUBMITTED", new String[]{"true"});
            } 
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
                        if (files.contains(filename+".thumb.jpg")) {
                            temPaths.add(uuid + "_" + pk + File.separator + filename+".thumb.jpg");
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
