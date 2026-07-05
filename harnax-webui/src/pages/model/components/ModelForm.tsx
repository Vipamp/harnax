import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { Form, Input, Select, Switch, Row, Col, Typography, Button } from 'antd';
import { createModel, updateModel } from '@/services/ant-design-pro/model';
import { message } from 'antd';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import { AppstoreOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';

const { Text } = Typography;

interface ModelFormProps {
  visible: boolean;
  values: API.ModelItem | null;
  providerId?: number;
  onCancel: () => void;
  onSuccess: () => void;
}

const MODEL_TYPE_OPTIONS = [
  { label: '对话模型', value: 'chat' },
  { label: '嵌入模型', value: 'embedding' },
];

const ModelForm: React.FC<ModelFormProps> = ({ visible, values, providerId, onCancel, onSuccess }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [modelType, setModelType] = useState<string | undefined>(values?.modelType);
  const { username, isAdmin } = getCurrentUserInfo();
  const isCreate = !values;

  useEffect(() => {
    if (visible) {
      if (values) {
        form.setFieldsValue({
          name: values.name,
          modelName: values.modelName,
          providerId: values.providerId,
          description: values.description,
          modelType: values.modelType,
          price: values.price,
          supportInternet: values.supportInternet === 1,
          supportReasoning: values.supportReasoning === 1,
          supportTool: values.supportTool === 1,
          supportMcp: values.supportMcp === 1,
          supportVision: values.supportVision === 1,
          status: values.status,
          isPublic: values.isPublic === 1,
        });
        setModelType(values.modelType);
      } else {
        form.resetFields();
        form.setFieldsValue({
          providerId: providerId,
          modelType: 'chat',
          price: 0,
          status: 1,
          isPublic: false,
        });
        setModelType('chat');
      }
    }
  }, [visible, values, providerId, form]);

  const handleModelTypeChange = (value: string) => {
    setModelType(value);
    if (value !== 'chat') {
      form.setFieldsValue({
        supportInternet: false,
        supportReasoning: false,
        supportTool: false,
        supportMcp: false,
        supportVision: false,
      });
    }
  };

  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);
      const data = {
        name: formValues.name,
        modelName: formValues.modelName,
        providerId: formValues.providerId,
        description: formValues.description,
        modelType: formValues.modelType,
        price: formValues.price ? parseFloat(formValues.price) : null,
        supportInternet: formValues.supportInternet ? 1 : 0,
        supportReasoning: formValues.supportReasoning ? 1 : 0,
        supportTool: formValues.supportTool ? 1 : 0,
        supportMcp: formValues.supportMcp ? 1 : 0,
        supportVision: formValues.supportVision ? 1 : 0,
        status: formValues.status,
        isPublic: formValues.isPublic ? 1 : 0,
      };
      if (values) {
        const response = await updateModel(values.id, data);
        if (response.code === 200) {
          message.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
          onSuccess();
        } else {
          message.error(response.message || intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed' }));
        }
      } else {
        const response = await createModel(data);
        if (response.code === 200) {
          message.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
          onSuccess();
        } else {
          message.error(response.message || intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed' }));
        }
      }
    } catch (error) {
      message.error(values ? intl.formatMessage({ id: 'pages.message.updateFailed', defaultMessage: 'Update failed' }) : intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed' }));
    } finally {
      setLoading(false);
    }
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="lg"
      titleConfig={{
        mainTitle: isCreate
          ? intl.formatMessage({ id: 'pages.common.add', defaultMessage: 'Add' }) + intl.formatMessage({ id: 'menu.context.model', defaultMessage: 'Model' })
          : intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' }) + intl.formatMessage({ id: 'menu.context.model', defaultMessage: 'Model' }),
        subtitle: isCreate
          ? intl.formatMessage({ id: 'pages.model.create.subtitle', defaultMessage: 'Add new model to provider, configure name and capabilities' })
          : intl.formatMessage({ id: 'pages.model.edit.subtitle', defaultMessage: 'Modify model configuration, changes take effect immediately' }),
        icon: <AppstoreOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
        iconShadowColor: 'rgba(79, 110, 247, 0.25)',
      }}
    >
      <Form 
        form={form} 
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
      >
        <Form.Item name="providerId" hidden>
          <Input type="hidden" />
        </Form.Item>
        
        <Form.Item name="name" label={intl.formatMessage({ id: 'pages.common.name', defaultMessage: 'Name' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.placeholder.input', defaultMessage: 'Please enter' }) + intl.formatMessage({ id: 'pages.common.name', defaultMessage: 'Name' }) }]}>
          <Input 
            placeholder={intl.formatMessage({ id: 'pages.placeholder.input', defaultMessage: 'Please enter' }) + intl.formatMessage({ id: 'pages.common.name', defaultMessage: 'Name' })} 
          />
        </Form.Item>
        
        <Form.Item name="modelName" label={intl.formatMessage({ id: 'pages.model.modelName', defaultMessage: 'Model Name' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.placeholder.input', defaultMessage: 'Please enter' }) + intl.formatMessage({ id: 'pages.model.modelName', defaultMessage: 'Model Name' }) }]}>
          <Input 
            placeholder={intl.formatMessage({ id: 'pages.placeholder.example', defaultMessage: 'e.g.: ' }) + 'gpt-4, qwen-max'} 
          />
        </Form.Item>

        <Form.Item name="modelType" label={intl.formatMessage({ id: 'pages.model.type', defaultMessage: 'Model Type' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' }) + intl.formatMessage({ id: 'pages.model.type', defaultMessage: 'Model Type' }) }]}>
          <Select
            placeholder={intl.formatMessage({ id: 'pages.placeholder.select', defaultMessage: 'Please select' }) + intl.formatMessage({ id: 'pages.model.type', defaultMessage: 'Model Type' })}
            options={MODEL_TYPE_OPTIONS.map(opt => ({
              label: intl.formatMessage({ id: `pages.model.${opt.value}`, defaultMessage: opt.label }),
              value: opt.value,
            }))}
            onChange={handleModelTypeChange} />
        </Form.Item>
        
        <Form.Item name="price" label={intl.formatMessage({ id: 'pages.model.price', defaultMessage: 'Price (CNY/M tokens)' })}>
          <Input 
            type="number" 
            step="0.0001" 
            placeholder={intl.formatMessage({ id: 'pages.placeholder.input', defaultMessage: 'Please enter' }) + intl.formatMessage({ id: 'pages.model.price', defaultMessage: 'Price' })} 
          />
        </Form.Item>

        <Form.Item name="description" label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}>
          <Input.TextArea 
            rows={2} 
            placeholder={intl.formatMessage({ id: 'pages.placeholder.input', defaultMessage: 'Please enter' }) + intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
          />
        </Form.Item>

        {modelType === 'chat' && (
          <Form.Item label={intl.formatMessage({ id: 'pages.model.capabilities', defaultMessage: 'Capabilities' })}>
            <Row gutter={[8, 8]}>
              <Col span={8}>
                <Form.Item name="supportInternet" valuePropName="checked" noStyle>
                  <Switch checkedChildren={intl.formatMessage({ id: 'pages.model.tag.internet', defaultMessage: 'Internet' })} unCheckedChildren={intl.formatMessage({ id: 'pages.model.tag.internet', defaultMessage: 'Internet' })} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="supportReasoning" valuePropName="checked" noStyle>
                  <Switch checkedChildren={intl.formatMessage({ id: 'pages.model.tag.reasoning', defaultMessage: 'Reasoning' })} unCheckedChildren={intl.formatMessage({ id: 'pages.model.tag.reasoning', defaultMessage: 'Reasoning' })} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="supportTool" valuePropName="checked" noStyle>
                  <Switch checkedChildren={intl.formatMessage({ id: 'pages.model.tag.tool', defaultMessage: 'Tool' })} unCheckedChildren={intl.formatMessage({ id: 'pages.model.tag.tool', defaultMessage: 'Tool' })} />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={[8, 8]} style={{ marginTop: '8px' }}>
              <Col span={8}>
                <Form.Item name="supportMcp" valuePropName="checked" noStyle>
                  <Switch checkedChildren={intl.formatMessage({ id: 'pages.agent.mcp', defaultMessage: 'MCP' })} unCheckedChildren={intl.formatMessage({ id: 'pages.agent.mcp', defaultMessage: 'MCP' })} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="supportVision" valuePropName="checked" noStyle>
                  <Switch checkedChildren={intl.formatMessage({ id: 'pages.model.tag.vision', defaultMessage: 'Vision' })} unCheckedChildren={intl.formatMessage({ id: 'pages.model.tag.vision', defaultMessage: 'Vision' })} />
                </Form.Item>
              </Col>
            </Row>
          </Form.Item>
        )}

        <Form.Item name="status" label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })} initialValue={1}>
          <Select
            options={[
              { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
              { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
            ]}
          />
        </Form.Item>

        <Form.Item name="isPublic" label={intl.formatMessage({ id: 'pages.model.isPublic', defaultMessage: 'Is Public' })}
          valuePropName="checked" initialValue={false}>
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.model.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.model.private', defaultMessage: 'Private' })}
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate)} />
        </Form.Item>

        <Form.Item wrapperCol={{ span: 24 }} style={{ marginBottom: 0 }}>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px', paddingTop: '10px', paddingLeft: '168px', borderTop: '1px solid var(--vip-border)' }}>
            <Button onClick={onCancel}>
              {intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
            </Button>
            <Button type="primary" onClick={handleSubmit} loading={loading}>
              {intl.formatMessage({ id: 'pages.common.submit', defaultMessage: 'Submit' })}
            </Button>
          </div>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default ModelForm;
