import React, { useState, useEffect } from 'react';
import { Modal, Form, Input, Select, Switch, Row, Col } from 'antd';
import { createModel, updateModel } from '@/services/ant-design-pro/model';
import { message } from 'antd';

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
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [modelType, setModelType] = useState<string | undefined>(values?.modelType);

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
        });
        setModelType(values.modelType);
      } else {
        form.resetFields();
        form.setFieldsValue({
          providerId: providerId,
          status: 1,
        });
        setModelType(undefined);
      }
    }
  }, [visible, values, providerId, form]);

  // 监听模型类型变化
  const handleModelTypeChange = (value: string) => {
    setModelType(value);
    // 如果不是对话模型，清空所有能力选项
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
      };

      if (values) {
        // 更新
        await updateModel(values.id, data);
        message.success('更新成功');
      } else {
        // 创建
        await createModel(data);
        message.success('创建成功');
      }

      onSuccess();
    } catch (error) {
      message.error(values ? '更新失败' : '创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title={values ? '编辑模型' : '新增模型'}
      open={visible}
      onCancel={onCancel}
      onOk={handleSubmit}
      confirmLoading={loading}
      destroyOnClose
      width={600}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="providerId" hidden>
          <Input type="hidden" />
        </Form.Item>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item
              name="name"
              label="名称"
              rules={[{ required: true, message: '请输入名称' }]}
            >
              <Input placeholder="请输入名称" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="modelName"
              label="模型名称"
              rules={[{ required: true, message: '请输入模型名称' }]}
            >
              <Input placeholder="如：gpt-4, qwen-max" />
            </Form.Item>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col span={12}>
            <Form.Item
              name="modelType"
              label="模型类型"
              rules={[{ required: true, message: '请选择模型类型' }]}
            >
              <Select 
                placeholder="请选择模型类型" 
                options={MODEL_TYPE_OPTIONS} 
                onChange={handleModelTypeChange}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="price"
              label="价格（元/百万token）"
            >
              <Input type="number" step="0.0001" placeholder="请输入价格" />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item
          name="description"
          label="描述"
        >
          <Input.TextArea rows={2} placeholder="请输入描述" />
        </Form.Item>

        {/* 只有对话模型才显示能力选项 */}
        {modelType === 'chat' && (
          <Form.Item label="模型能力">
            <Row gutter={16}>
              <Col span={8}>
                <Form.Item name="supportInternet" valuePropName="checked" noStyle>
                  <Switch checkedChildren="联网" unCheckedChildren="联网" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="supportReasoning" valuePropName="checked" noStyle>
                  <Switch checkedChildren="推理" unCheckedChildren="推理" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="supportTool" valuePropName="checked" noStyle>
                  <Switch checkedChildren="工具" unCheckedChildren="工具" />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={16} style={{ marginTop: '8px' }}>
              <Col span={8}>
                <Form.Item name="supportMcp" valuePropName="checked" noStyle>
                  <Switch checkedChildren="MCP" unCheckedChildren="MCP" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="supportVision" valuePropName="checked" noStyle>
                  <Switch checkedChildren="视觉" unCheckedChildren="视觉" />
                </Form.Item>
              </Col>
            </Row>
          </Form.Item>
        )}

        {/* 状态选择 */}
        <Form.Item
          name="status"
          label="状态"
          initialValue={1}
        >
          <Select
            options={[
              { label: '启用', value: 1 },
              { label: '禁用', value: 0 },
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default ModelForm;
