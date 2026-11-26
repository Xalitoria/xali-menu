import { boot } from './app.js'

boot().catch((error) => {
  const root = document.getElementById('app')
  if (root) root.textContent = `The editor could not start: ${error.message}`
})
