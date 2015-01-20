package com.eddysystems.eddy;

import javax.swing.*;

public class PreferencesForm {

  private JCheckBox applyAutomaticallyOnEnterCheckBox;
  private JPanel panel;
  private JTextField autoApplyThresholdTextField;
  private JTextField autoApplyFactorTextField;

  public PreferencesForm() {
  }

  public JPanel getPanel() {
    return panel;
  }

  public void setData(PreferenceData data) {
    applyAutomaticallyOnEnterCheckBox.setSelected(data.isAutoApply());
    autoApplyThresholdTextField.setText(data.getAutoApplyThreshold());
    autoApplyFactorTextField.setText(data.getAutoApplyFactor());
  }

  public void getData(PreferenceData data) {
    data.setAutoApply(applyAutomaticallyOnEnterCheckBox.isSelected());
    data.setAutoApplyThreshold(autoApplyThresholdTextField.getText());
    data.setAutoApplyFactor(autoApplyFactorTextField.getText());
  }

  public boolean isModified(PreferenceData data) {
    if (applyAutomaticallyOnEnterCheckBox.isSelected() != data.isAutoApply()) return true;
    if (autoApplyThresholdTextField.getText() != null ? !autoApplyThresholdTextField.getText().equals(data.getAutoApplyThreshold()) : data.getAutoApplyThreshold() != null)
      return true;
    if (autoApplyFactorTextField.getText() != null ? !autoApplyFactorTextField.getText().equals(data.getAutoApplyFactor()) : data.getAutoApplyFactor() != null)
      return true;
    return false;
  }
}