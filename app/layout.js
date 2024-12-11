import '../styles/globals.css';

import Header from '../components/Header';
import Footer from '../components/Footer';

export const metadata = {
  title: 'IthaMedia',
  description: 'Découvrez des contenus fiables et éducatifs.',
};

export default function RootLayout({ children }) {
  return (
    <html lang="fr">
      <body>
        <Header />
        <main className="bg-background min-h-screen">{children}</main>
        <Footer />
      </body>
    </html>
  );
}
