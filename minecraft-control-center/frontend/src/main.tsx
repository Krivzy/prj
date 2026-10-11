import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import App from './App';
import {MotionConfig} from 'motion/react';
import './style.css';
import AnimatedPixelCursor from './AnimatedPixelCursor';
createRoot(document.getElementById('root')!).render(<StrictMode><MotionConfig reducedMotion="user"><App/><AnimatedPixelCursor/></MotionConfig></StrictMode>);
