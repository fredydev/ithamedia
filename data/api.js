import axios from 'axios';

const API_BASE_URL = 'http://localhost:5000/api'; // Change selon ton endpoint

// Récupérer toutes les chaînes
export const fetchChannels = async () => {
  try {
    const response = await axios.get(`${API_BASE_URL}/channels`);
    return response.data;
  } catch (error) {
    console.error('Erreur lors de la récupération des chaînes :', error);
    throw error;
  }
};

// Ajouter une chaîne (si besoin)
export const addChannel = async (channelData) => {
  try {
    const response = await axios.post(`${API_BASE_URL}/channels`, channelData);
    return response.data;
  } catch (error) {
    console.error('Erreur lors de l’ajout de la chaîne :', error);
    throw error;
  }
};
