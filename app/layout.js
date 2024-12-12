import '../styles/globals.css';

import Header from '../components/Header';
import Footer from '../components/Footer';
import Sidebar from '../components/Sidebar';
import FiltrePays from '../components/FiltrePays';

export const metadata = {
  title: 'IthaMedia',
  description: 'Découvrez des contenus fiables et éducatifs.',
};

export default function RootLayout({ children }) {
  return (
    <html lang="fr">
      <body className="">
        {/* Sidebar pour Desktop */}
        <Sidebar />
        {/* Contenu principal */}
        <div className="flex-1 md:ml-64"> {/* Ajout d'un margin gauche pour desktop */}
        <Header />
          <div className="container mx-auto mt-16 p-4">
            <FiltrePays />
            {children}
          </div>
        </div>
        {/* <Footer /> */}
      </body>
    </html>
  );
}


