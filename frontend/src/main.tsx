import React from "react";
import ReactDOM from "react-dom/client";
import { createTheme, MantineProvider } from "@mantine/core";
import "@mantine/core/styles.css";
import "./styles.css";
import App from "./App";

const theme = createTheme({
  fontFamily:
    'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
  primaryColor: "indigo",
  defaultRadius: "md",
  colors: {
    indigo: [
      "#f0f0fd",
      "#e0e1fa",
      "#c1c3f2",
      "#a0a3e9",
      "#8286df",
      "#686dd5",
      "#5258c8",
      "#4449ad",
      "#383d91",
      "#2e3276",
    ],
  },
  components: {
    Button: { defaultProps: { fw: 500 } },
    InputWrapper: { defaultProps: { size: "sm" } },
  },
});
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <MantineProvider theme={theme} forceColorScheme="light">
      <App />
    </MantineProvider>
  </React.StrictMode>,
);
