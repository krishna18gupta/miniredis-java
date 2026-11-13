import React from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { ThemeProvider } from './context/ThemeContext';
import { Navbar } from './components/layout/Navbar';
import { Footer } from './components/layout/Footer';
import { HomePage } from './pages/HomePage';
import { FeaturesPage } from './pages/FeaturesPage';
import { ArchitecturePage } from './pages/ArchitecturePage';
import { DocumentationPage } from './pages/DocumentationPage';
import { CommandsPage } from './pages/CommandsPage';
import { BenchmarksPage } from './pages/BenchmarksPage';
import { DownloadsPage } from './pages/DownloadsPage';
import { AboutPage } from './pages/AboutPage';
import { DashboardPage } from './pages/DashboardPage';
import { NotFoundPage } from './pages/NotFoundPage';

export const App: React.FC = () => {
  return (
    <ThemeProvider>
      <BrowserRouter>
        <div className="flex flex-col min-h-screen bg-zinc-50 text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100 transition-colors">
          <Navbar />
          <main className="flex-grow">
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/features" element={<FeaturesPage />} />
              <Route path="/architecture" element={<ArchitecturePage />} />
              <Route path="/docs" element={<DocumentationPage />} />
              <Route path="/commands" element={<CommandsPage />} />
              <Route path="/benchmarks" element={<BenchmarksPage />} />
              <Route path="/downloads" element={<DownloadsPage />} />
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/about" element={<AboutPage />} />
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </main>
          <Footer />
        </div>
      </BrowserRouter>
    </ThemeProvider>
  );
};

export default App;
