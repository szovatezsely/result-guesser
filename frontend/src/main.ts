import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import MatchList from './views/MatchList.vue'
import MatchDetail from './views/MatchDetail.vue'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'matches', component: MatchList },
    { path: '/match/:id', name: 'match', component: MatchDetail, props: true },
  ],
})

createApp(App).use(router).mount('#app')
