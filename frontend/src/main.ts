import { createApp, h } from 'vue';
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElCheckboxGroup,
  ElConfigProvider,
  ElDialog,
  ElEmpty,
  ElLoading,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import './styles.css';
import App from './StudioShell.vue';

const app = createApp(() => h(ElConfigProvider, { locale: zhCn }, () => h(App)));
for (const component of [
  ElAlert,
  ElButton,
  ElCheckbox,
  ElCheckboxGroup,
  ElDialog,
  ElEmpty,
  ElTable,
  ElTableColumn,
  ElTag,
]) {
  app.component(component.name!, component);
}
app.use(ElLoading).mount('#app');
