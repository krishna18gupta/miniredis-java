import { useEffect } from 'react';
import { SITE_CONFIG } from '../../config/site';

interface SeoMetaProps {
  title?: string;
  description?: string;
}

export const SeoMeta: React.FC<SeoMetaProps> = ({ title, description }) => {
  useEffect(() => {
    const fullTitle = title ? `${title} | ${SITE_CONFIG.name}` : `${SITE_CONFIG.name} | ${SITE_CONFIG.tagline}`;
    document.title = fullTitle;

    if (description) {
      let metaDesc = document.querySelector('meta[name="description"]');
      if (metaDesc) {
        metaDesc.setAttribute('content', description);
      }
    }
    // Scroll to top on page transition
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [title, description]);

  return null;
};
